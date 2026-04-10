package de.mcsrswap;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.mcsrswap.mixin.PlayerManagerInvoker;
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
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.SetCameraEntityS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
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

    /** Last known game mode – used to detect Spectator transitions. */
    private final Map<UUID, GameMode> lastKnownGameMode = new HashMap<>();

    /**
     * Players who are about to join as locked spectators (watchers). Velocity sends
     * "incoming_spectator" before the watcher connects, so ENTITY_LOAD can immediately put them in
     * spectator mode and skip state processing.
     */
    private final Set<UUID> pendingSpectators = new HashSet<>();

    /**
     * Players currently connected to this server. ENTITY_LOAD also fires on dimension changes and
     * respawns – we only act on the actual first join.
     */
    private final Set<UUID> connectedPlayers = new HashSet<>();

    /**
     * Players for whom the slot .dat has already been explicitly written by the {@code save}
     * handler. Their subsequent disconnect save is redirected to their real UUID file instead of
     * the slot file, so the slot .dat is never overwritten with post-ejection state (which would
     * lack the {@code RootVehicle} tag and have stale position data).
     */
    public static final Set<UUID> bypassSlotRedirect = new HashSet<>();

    /** Counter for the 20-tick periodic save cycle. */
    private int stateSaveTick = 0;

    // =========================
    // INIT
    // =========================

    @Override
    public void onInitialize() {
        Lang.init();

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

                    // Incoming watcher: put in spectator immediately, skip everything else.
                    // The "incoming_spectator" pre-notification from Velocity already marked them.
                    if (pendingSpectators.remove(player.getUuid())) {
                        player.interactionManager.setGameMode(
                                GameMode.SPECTATOR, player.interactionManager.getGameMode());
                        sendModeToVelocity(player, true);
                        ServerPlayerEntity toWatch =
                                findActiveSurvivalPlayer(server, player.getUuid());
                        if (toWatch != null) {
                            player.networkHandler.sendPacket(new SetCameraEntityS2CPacket(toWatch));
                            spectatorCameras.put(player.getUuid(), toWatch.getUuid());
                        }
                        return;
                    }

                    // Player loaded their data from the slot UUID .dat automatically.
                    // If they were somehow in spectator (e.g. first join before game start), notify
                    // Velocity and skip further processing.
                    if (player.interactionManager.getGameMode() == GameMode.SPECTATOR) {
                        sendModeToVelocity(player, true);
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

                    stateSaveTick++;
                    if (stateSaveTick >= 20) {
                        stateSaveTick = 0;
                        Set<UUID> online = new HashSet<>();
                        PlayerManagerInvoker pmInvoker =
                                (PlayerManagerInvoker) srv.getPlayerManager();
                        for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                            online.add(p.getUuid());
                            GameMode mode = p.interactionManager.getGameMode();
                            GameMode prev = lastKnownGameMode.put(p.getUuid(), mode);
                            if (prev != null && prev != mode) {
                                sendModeToVelocity(p, mode == GameMode.SPECTATOR);
                            }
                            // Periodically persist the slot state for living survival players.
                            if (mode != GameMode.SPECTATOR && p.getHealth() > 0.0f) {
                                pmInvoker.invokeSavePlayerData(p);
                            }
                        }
                        // Cleanup disconnected players
                        connectedPlayers.stream()
                                .filter(u -> !online.contains(u))
                                .forEach(Lang::removeLocale);
                        connectedPlayers.retainAll(online);
                        lastKnownGameMode.keySet().retainAll(online);
                        pendingSpectators.retainAll(online);
                        bypassSlotRedirect.retainAll(online);
                        stateManager.cleanupDisconnected(online);
                        spectatorCameras.keySet().retainAll(online);

                        // Re-lock spectator cameras every second so the watcher cannot escape POV
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
                            watcher.networkHandler.sendPacket(new SetCameraEntityS2CPacket(target));
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

    /** Notifies Velocity of a game-mode change (spectator=true → Spectator, false → Survival). */
    private void sendModeToVelocity(ServerPlayerEntity player, boolean spectator) {
        sendToVelocity(
                player,
                out -> {
                    out.writeUTF("mode");
                    out.writeUTF(player.getUuid().toString());
                    out.writeUTF(spectator ? "spectator" : "survival");
                });
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

            case "save":
                // Triggered by Velocity 50 ms before rotation. Force-write the slot .dat so the
                // next player gets the freshest possible state including RootVehicle (if riding).
                // After saving, add the player to bypassSlotRedirect so their subsequent
                // disconnect-save goes to their personal file, not the slot – this prevents the
                // disconnect (which fires after stopRiding) from overwriting the slot .dat without
                // the RootVehicle tag.
                PlayerManagerInvoker pmInvoker = (PlayerManagerInvoker) server.getPlayerManager();
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p.interactionManager.getGameMode() == GameMode.SPECTATOR) continue;
                    if (p.getHealth() <= 0.0f) continue;
                    if (stateManager.saveHotbar) stateManager.captureHotbarPreference(p);
                    stateManager.lastPlayerUuid = p.getUuid();
                    pmInvoker.invokeSavePlayerData(p);
                    bypassSlotRedirect.add(p.getUuid());
                }
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
                pendingSpectators.add(UUID.fromString(in.readUTF()));
                return;

            case "become_spectator":
                {
                    UUID uuid = UUID.fromString(in.readUTF());
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(uuid);
                    if (target == null) return;
                    // Fallback: if incoming_spectator pre-notification was missed, handle it here.
                    target.interactionManager.setGameMode(
                            GameMode.SPECTATOR, target.interactionManager.getGameMode());
                    sendModeToVelocity(target, true);
                    ServerPlayerEntity toWatch = findActiveSurvivalPlayer(server, uuid);
                    if (toWatch != null) {
                        target.networkHandler.sendPacket(new SetCameraEntityS2CPacket(toWatch));
                        spectatorCameras.put(uuid, toWatch.getUuid());
                    }
                    return;
                }

            case "prepare_return":
                {
                    UUID uuid = UUID.fromString(in.readUTF());
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(uuid);
                    if (target == null) return;
                    spectatorCameras.remove(uuid);
                    target.teleport(
                            target.getServerWorld(),
                            target.getX(),
                            100000,
                            target.getZ(),
                            target.yaw,
                            target.pitch);
                    target.interactionManager.setGameMode(GameMode.SURVIVAL, GameMode.SPECTATOR);
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
