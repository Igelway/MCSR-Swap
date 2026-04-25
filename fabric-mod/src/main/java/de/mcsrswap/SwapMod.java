package de.mcsrswap;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.mcsrswap.mixin.PlayerManagerInvoker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.SetCameraEntityS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

public class SwapMod implements ModInitializer {

    /** Must match VelocitySwapPlugin.CHANNEL: "mcsrswap:main" */
    public static final Identifier CHANNEL = new Identifier("mcsrswap", "main");

    /** Singleton instance – used by EndPortalMixin to call back into game logic. */
    public static SwapMod INSTANCE;

    private MinecraftServer server;

    private int currentTime = 0;
    private int completedWorlds = 0;
    private int requiredWorlds = 0;
    private boolean finished = false;

    /**
     * true while time is frozen at 0 (server start / after reset); cleared on first player join.
     */
    private boolean daylightCycleFrozen = false;

    /** true when game ticks are frozen – set by reset, cleared by first survival player join. */
    private boolean frozen = true;

    /**
     * Players set to locked-spectator mode via "become_spectator" from Velocity. Their camera is
     * periodically re-locked to the active survival player. Maps watcher UUID → UUID of the player
     * they are locked to.
     */
    private final Map<UUID, UUID> spectatorCameras = new HashMap<>();

    private final ScoreboardManager scoreboardManager = new ScoreboardManager();
    final StateManager stateManager = new StateManager();

    /**
     * Players who are about to join as locked spectators (watchers). Velocity sends
     * "incoming_spectator" before the watcher connects, so ENTITY_LOAD can skip survival processing
     * for them. Actual spectator mode is applied on the first tick (deferred) to guarantee it runs
     * after {@code sendWorldInfo}, which would otherwise override the game mode.
     */
    private final Set<UUID> pendingSpectators = new HashSet<>();

    /**
     * Players in {@link #pendingSpectators} who have already fired ENTITY_LOAD and are waiting for
     * the next tick to receive their spectator mode change (so it lands after {@code
     * sendWorldInfo}).
     */
    private final Set<UUID> deferredSpectatorSwitch = new HashSet<>();

    /**
     * Players currently connected to this server. ENTITY_LOAD also fires on dimension changes and
     * respawns – we only act on the actual first join.
     */
    private final Set<UUID> connectedPlayers = new HashSet<>();

    /** Counter for the 20-tick periodic save cycle. */
    private int stateSaveTick = 0;

    // =========================
    // INIT
    // =========================

    /**
     * Reads the server's slot name from the environment variable {@code MCSRSWAP_SLOT} or from
     * {@code config/mcsrswap/slot-name.txt} and pre-computes {@link ModConfig#slotUuid}.
     *
     * <p>This must run before any player connects because {@code ENTITY_LOAD} (which triggers
     * player-data loading) fires before the {@code slot_uuid} plugin message from Velocity can
     * arrive. Without early initialisation, the first player to join each server after a restart
     * loads their personal {@code .dat} file instead of the shared slot file.
     *
     * <p>Velocity still sends {@code slot_uuid} on every connect; that message overrides the value
     * set here (they should be identical since both sides use the same name-based formula).
     */
    private static void initSlotUuid() {
        String slotName = System.getenv("MCSRSWAP_SLOT");
        if (slotName == null || slotName.isBlank()) {
            Path cfg = FabricLoader.getInstance().getConfigDir().resolve("mcsrswap/slot-name.txt");
            if (Files.exists(cfg)) {
                try {
                    slotName = Files.readString(cfg, StandardCharsets.UTF_8).strip();
                } catch (IOException ignored) {
                }
            }
        }
        if (slotName != null && !slotName.isBlank()) {
            ModConfig.slotUuid =
                    UUID.nameUUIDFromBytes(
                            ("mcsrswap-slot-" + slotName).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onInitialize() {
        Lang.init();
        initSlotUuid();

        ServerLifecycleEvents.SERVER_STARTED.register(
                srv -> {
                    INSTANCE = this;
                    server = srv;
                    scoreboardManager.setupScoreboard(srv);
                    freezeWorldTime();
                });

        // Incoming plugin messages from Velocity via the v0 networking API
        ServerSidePacketRegistry.INSTANCE.register(
                CHANNEL,
                (ctx, buf) -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    ctx.getTaskQueue().execute(() -> handleMessage(bytes));
                });

        // ENTITY_LOAD fires on: first join, dimension changes, and respawns.
        // We only act on the actual first join (tracked via connectedPlayers).
        ServerEntityEvents.ENTITY_LOAD.register(
                (entity, world) -> {
                    if (!(entity instanceof ServerPlayerEntity)) return;
                    ServerPlayerEntity player = (ServerPlayerEntity) entity;

                    if (connectedPlayers.contains(player.getUuid())) return;
                    connectedPlayers.add(player.getUuid());

                    // Incoming watcher: skip survival processing. Defer the actual mode switch to
                    // the next tick so it lands after sendWorldInfo (which would override it).
                    if (pendingSpectators.contains(player.getUuid())) {
                        deferredSpectatorSwitch.add(player.getUuid());
                        return;
                    }

                    // Unfreeze day/night cycle and game ticks on first survival join after reset.
                    if (daylightCycleFrozen) {
                        daylightCycleFrozen = false;
                        server.getWorlds()
                                .forEach(
                                        w ->
                                                w.getGameRules()
                                                        .get(GameRules.DO_DAYLIGHT_CYCLE)
                                                        .set(true, server));
                    }
                    frozen = false;

                    // Always force survival – the slot .dat may contain a different game mode.
                    player.interactionManager.setGameMode(
                            GameMode.SURVIVAL, player.interactionManager.getGameMode());

                    scoreboardManager.update(
                            finished, completedWorlds, requiredWorlds, currentTime, player);

                    // Transfer mob anger from the previous player to the incoming player.
                    UUID prevUuid = stateManager.lastPlayerUuid;
                    if (prevUuid != null && !prevUuid.equals(player.getUuid())) {
                        stateManager.transferMobAnger(
                                player.getServerWorld(),
                                prevUuid,
                                player.getUuid(),
                                player.getX(),
                                player.getY(),
                                player.getZ());
                    }

                    // Apply the joining player's own hotbar preference to the slot inventory.
                    if (stateManager.saveHotbar) stateManager.applyHotbarPreference(player);

                    // Disable join invincibility.
                    player.timeUntilRegen = 0;
                    stateManager.clearRegenAfter.put(player.getUuid(), 20);
                });

        // Tick handler: clearRegen, mode detection, periodic save, disconnect cleanup, camera locks
        ServerTickEvents.END_SERVER_TICK.register(
                srv -> {
                    if (frozen) return;

                    stateManager.tickClearRegen(srv);

                    // Apply deferred spectator switches (scheduled in ENTITY_LOAD).
                    // Runs every tick to avoid a 1-second delay; cleans itself up.
                    if (!deferredSpectatorSwitch.isEmpty()) {
                        for (UUID uuid : new HashSet<>(deferredSpectatorSwitch)) {
                            ServerPlayerEntity watcher = srv.getPlayerManager().getPlayer(uuid);
                            if (watcher == null) continue;
                            watcher.interactionManager.setGameMode(
                                    GameMode.SPECTATOR, watcher.interactionManager.getGameMode());
                            ServerPlayerEntity toWatch = findActiveSurvivalPlayer(srv, uuid);
                            if (toWatch != null) {
                                watcher.networkHandler.sendPacket(
                                        new SetCameraEntityS2CPacket(toWatch));
                                spectatorCameras.put(uuid, toWatch.getUuid());
                            }
                            pendingSpectators.remove(uuid);
                            deferredSpectatorSwitch.remove(uuid);
                        }
                    }

                    stateSaveTick++;
                    if (stateSaveTick >= 20) {
                        stateSaveTick = 0;
                        Set<UUID> online = new HashSet<>();
                        PlayerManagerInvoker pmInvoker =
                                (PlayerManagerInvoker) srv.getPlayerManager();
                        for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                            online.add(p.getUuid());
                            // Periodically persist the slot state for living survival players.
                            if (p.interactionManager.getGameMode() != GameMode.SPECTATOR
                                    && p.getHealth() > 0.0f) {
                                pmInvoker.invokeSavePlayerData(p);
                            }
                        }
                        // Cleanup disconnected players
                        connectedPlayers.stream()
                                .filter(u -> !online.contains(u))
                                .forEach(Lang::removeLocale);
                        connectedPlayers.retainAll(online);
                        pendingSpectators.retainAll(online);
                        deferredSpectatorSwitch.retainAll(online);
                        stateManager.cleanupDisconnected(online);
                        spectatorCameras.keySet().retainAll(online);

                        // Re-lock spectator cameras every second so the watcher cannot escape POV.
                        // Also follows the target across dimensions via teleport.
                        for (Map.Entry<UUID, UUID> cam : spectatorCameras.entrySet()) {
                            ServerPlayerEntity watcher =
                                    srv.getPlayerManager().getPlayer(cam.getKey());
                            ServerPlayerEntity target =
                                    srv.getPlayerManager().getPlayer(cam.getValue());
                            if (watcher == null) continue;
                            if (target == null) {
                                target = findActiveSurvivalPlayer(srv, cam.getKey());
                                if (target == null) continue;
                                cam.setValue(target.getUuid());
                            }
                            // Teleport watcher into target's dimension if they differ.
                            if (watcher.world != target.world) {
                                watcher.teleport(
                                        (ServerWorld) target.world,
                                        target.getX(),
                                        target.getY(),
                                        target.getZ(),
                                        target.getYaw(1.0f),
                                        target.getPitch(1.0f));
                            } else {
                                watcher.networkHandler.sendPacket(
                                        new SetCameraEntityS2CPacket(target));
                            }
                        }
                    }
                });
    }

    // =========================
    // PORTAL DETECTION (game objective)
    // =========================

    /** Called by EndPortalMixin when a living survival player steps into the End exit portal. */
    public static void onEndPortalEntered(ServerPlayerEntity player) {
        if (INSTANCE != null) INSTANCE.onPlayerExitEnd(player);
    }

    /**
     * Called by DisconnectMessageMixin before vanilla's disconnect-save. Captures hotbar preference
     * and records the last survival player UUID for mob-anger inheritance.
     */
    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        if (INSTANCE == null) return;
        if (player.interactionManager.getGameMode() == GameMode.SPECTATOR) return;
        if (player.getHealth() <= 0.0f) return;
        StateManager sm = INSTANCE.stateManager;
        if (sm.saveHotbar) sm.captureHotbarPreference(player);
        sm.lastPlayerUuid = player.getUuid();
    }

    private void onPlayerExitEnd(ServerPlayerEntity player) {
        if (finished) return;
        finished = true;
        server.getPlayerManager()
                .getPlayerList()
                .forEach(
                        p -> p.sendMessage(new LiteralText(Lang.gameFinished(p.getUuid())), false));
        scoreboardManager.update(finished, completedWorlds, requiredWorlds, currentTime);
        sendFinish(player);
    }

    private void sendFinish(ServerPlayerEntity player) {
        sendToVelocity(player, out -> out.writeUTF("finish"));
    }

    private void sendToVelocity(ServerPlayerEntity player, Consumer<ByteArrayDataOutput> writer) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        writer.accept(out);
        PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBytes(out.toByteArray());
        Packet<?> packet = ServerSidePacketRegistry.INSTANCE.toPacket(CHANNEL, buf);
        ServerSidePacketRegistry.INSTANCE.sendToPlayer(player, packet);
    }

    // =========================
    // MESSAGING (Velocity → Fabric-Mod)
    // =========================

    /** Called by SpectatorLockMixin to check if a player's camera lock should be enforced. */
    public static boolean isSpectatorLocked(UUID uuid) {
        return INSTANCE != null && INSTANCE.spectatorCameras.containsKey(uuid);
    }

    /** Called by PlayerDataMixin to skip slot-UUID redirect for incoming spectators. */
    public static boolean isIncomingSpectator(UUID uuid) {
        return INSTANCE != null && INSTANCE.pendingSpectators.contains(uuid);
    }

    /** Returns the first survival player on the server who is NOT excludeUuid. */
    private ServerPlayerEntity findActiveSurvivalPlayer(MinecraftServer srv, UUID excludeUuid) {
        for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
            if (!p.getUuid().equals(excludeUuid)
                    && p.interactionManager.getGameMode() == GameMode.SURVIVAL) {
                return p;
            }
        }
        return null;
    }

    private void handleMessage(byte[] bytes) {
        ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
        String sub = in.readUTF();

        switch (sub) {
            case "time":
                currentTime = in.readInt();
                break;

            case "progress":
                completedWorlds = in.readInt();
                requiredWorlds = in.readInt();
                break;

            case "reset":
                resetWorldState();
                frozen = true;
                return;

            case "savehotbar":
                stateManager.saveHotbar = in.readBoolean();
                return;

            case "eyehoverticks":
                ModConfig.eyeHoverTicks = in.readInt();
                return;

            case "slot_uuid":
                ModConfig.slotUuid = UUID.fromString(in.readUTF());
                return;

            case "incoming_spectator":
                {
                    UUID uuid = UUID.fromString(in.readUTF());
                    pendingSpectators.add(uuid);
                    // Late-arrival: if the player is already online, apply spectator immediately.
                    ServerPlayerEntity alreadyHere = server.getPlayerManager().getPlayer(uuid);
                    if (alreadyHere != null && !deferredSpectatorSwitch.contains(uuid)) {
                        alreadyHere.interactionManager.setGameMode(
                                GameMode.SPECTATOR, alreadyHere.interactionManager.getGameMode());
                        ServerPlayerEntity toWatch = findActiveSurvivalPlayer(server, uuid);
                        if (toWatch != null) {
                            alreadyHere.networkHandler.sendPacket(
                                    new SetCameraEntityS2CPacket(toWatch));
                            spectatorCameras.put(uuid, toWatch.getUuid());
                        }
                        pendingSpectators.remove(uuid);
                    }
                    return;
                }

            case "become_spectator":
                {
                    UUID uuid = UUID.fromString(in.readUTF());
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(uuid);
                    if (target == null) return;
                    // Fallback: ensure spectator mode regardless of deferred state.
                    target.interactionManager.setGameMode(
                            GameMode.SPECTATOR, target.interactionManager.getGameMode());
                    ServerPlayerEntity toWatch = findActiveSurvivalPlayer(server, uuid);
                    if (toWatch != null) {
                        target.networkHandler.sendPacket(new SetCameraEntityS2CPacket(toWatch));
                        spectatorCameras.put(uuid, toWatch.getUuid());
                    }
                    pendingSpectators.remove(uuid);
                    deferredSpectatorSwitch.remove(uuid);
                    return;
                }

            case "prepare_return":
                {
                    UUID uuid = UUID.fromString(in.readUTF());
                    // Release camera lock so the client gets SetCameraEntityS2CPacket(self)
                    // before disconnecting. Prevents frozen/stuck camera on the home server.
                    spectatorCameras.remove(uuid);
                    pendingSpectators.remove(uuid);
                    deferredSpectatorSwitch.remove(uuid);
                    ServerPlayerEntity watcher = server.getPlayerManager().getPlayer(uuid);
                    if (watcher != null) {
                        watcher.networkHandler.sendPacket(new SetCameraEntityS2CPacket(watcher));
                    }
                    return;
                }
        }

        scoreboardManager.update(finished, completedWorlds, requiredWorlds, currentTime);
    }

    // =========================
    // RESET
    // =========================

    private void resetWorldState() {
        finished = false;
        server.getPlayerManager()
                .getPlayerList()
                .forEach(p -> p.sendMessage(new LiteralText(Lang.newRound(p.getUuid())), false));
        freezeWorldTime();
        scoreboardManager.update(finished, completedWorlds, requiredWorlds, currentTime);
    }

    /** Freezes time at 0 and stops the day/night cycle until a player joins. */
    private void freezeWorldTime() {
        daylightCycleFrozen = true;
        server.getWorlds()
                .forEach(
                        world -> {
                            world.setTimeOfDay(0);
                            world.getGameRules()
                                    .get(GameRules.DO_DAYLIGHT_CYCLE)
                                    .set(false, server);
                        });
    }
}
