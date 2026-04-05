package de.mcsrswap;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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

    // Game state (identical to the Paper plugin)
    private int currentTime = 0;
    private int completedWorlds = 0;
    private int requiredWorlds = 0;
    private boolean finished = false;

    /**
     * true while time is frozen at 0 (server start / after reset); cleared on first player join.
     */
    private boolean daylightCycleFrozen = false;

    /** true when server is fully started and ready for players. */
    private boolean serverReady = false;

    /**
     * true when game is frozen (no ticks processed) – set by reset, cleared by first player join or
     * start.
     */
    private boolean frozen = true;

    /**
     * Players set to locked-spectator mode via "become_spectator" from Velocity. Their camera is
     * periodically re-locked to the active survival player. Maps watcher UUID → UUID of the player
     * they are locked to.
     */
    private final Map<UUID, UUID> spectatorCameras = new HashMap<>();

    /**
     * Players currently in a dead state (health ≤ 0). Used to detect the exact tick of death so we
     * can clear the saved inventory and prevent item duplication on swap (items remain on the floor
     * in the world; the next player starts with an empty inventory and can pick them up).
     */
    private final Set<UUID> currentlyDead = new HashSet<>();

    private final ScoreboardManager scoreboardManager = new ScoreboardManager();
    private final StateManager stateManager = new StateManager();

    /** Last known game mode – used to detect Spectator transitions. */
    private final Map<UUID, GameMode> lastKnownGameMode = new HashMap<>();

    /**
     * Players who are about to join as locked spectators (watchers). Velocity sends
     * "incoming_spectator" before the watcher connects, so ENTITY_LOAD can immediately skip the
     * normal state restore and put them in spectator mode.
     */
    private final Set<UUID> pendingSpectators = new HashSet<>();

    /**
     * Players currently connected to this server. ENTITY_LOAD also fires on dimension changes and
     * respawns – restoreState should only be called on the actual first join.
     */
    private final Set<UUID> connectedPlayers = new HashSet<>();

    /**
     * Deferred state restore: ENTITY_LOAD schedules the restore for X ticks later. This gives the
     * client connection time to stabilise and prevents an interaction freeze.
     */
    private final Map<UUID, Integer> restoreCountdown = new HashMap<>();

    private final Map<UUID, PlayerState> pendingRestore = new HashMap<>();

    private static final int RESTORE_DELAY_TICKS = 2;

    // =========================
    // INIT
    // =========================

    /** State saving: saved every 20 ticks (1 s) so the disconnect state is always up to date. */
    private int stateSaveTick = 0;

    @Override
    public void onInitialize() {
        Lang.init();

        ServerLifecycleEvents.SERVER_STARTED.register(
                srv -> {
                    INSTANCE = this;
                    server = srv;
                    scoreboardManager.setupScoreboard(srv);
                    stateManager.setServer(srv);
                    freezeWorldTime();

                    // Server is now ready - Velocity will detect this via ping
                    serverReady = true;
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
        // Do NOT set lastKnownWorld here (would break End-portal detection)!
        // Only call restoreState on the actual first join; Spectator players are skipped.
        ServerEntityEvents.ENTITY_LOAD.register(
                (entity, world) -> {
                    if (!(entity instanceof ServerPlayerEntity)) return;
                    ServerPlayerEntity player = (ServerPlayerEntity) entity;

                    if (!connectedPlayers.contains(player.getUuid())) {
                        connectedPlayers.add(player.getUuid());

                        // Incoming watcher: skip state restore entirely, put in spectator
                        // immediately.
                        // This is triggered by a "incoming_spectator" pre-notification from
                        // Velocity,
                        // sent before the player's connection request fires.
                        if (pendingSpectators.remove(player.getUuid())) {
                            player.interactionManager.setGameMode(
                                    GameMode.SPECTATOR, player.interactionManager.getGameMode());
                            sendModeToVelocity(player, true);
                            ServerPlayerEntity toWatch =
                                    findActiveSurvivalPlayer(server, player.getUuid());
                            if (toWatch != null) {
                                player.networkHandler.sendPacket(
                                        new SetCameraEntityS2CPacket(toWatch));
                                spectatorCameras.put(player.getUuid(), toWatch.getUuid());
                            }
                            return;
                        }

                        boolean spectator =
                                player.interactionManager.getGameMode() == GameMode.SPECTATOR;
                        if (spectator) {
                            sendModeToVelocity(player, true);
                            return; // Spectator: no state restore, no scoreboard update
                        }
                        // Unfreeze day/night cycle and game when first survival player joins after
                        // a reset
                        if (daylightCycleFrozen) {
                            daylightCycleFrozen = false;
                            server.getWorlds()
                                    .forEach(
                                            w ->
                                                    w.getGameRules()
                                                            .get(GameRules.DO_DAYLIGHT_CYCLE)
                                                            .set(true, server));
                        }
                        if (frozen) {
                            frozen = false;
                        }
                        scoreboardManager.update(
                                finished, completedWorlds, requiredWorlds, currentTime, player);
                        PlayerState cs = stateManager.getCurrentState();
                        if (cs != null) {
                            // Capture joining player's own hotbar preference NOW (their own server
                            // NBT)
                            // before restoreState overwrites the inventory with the predecessor's
                            // items.
                            if (stateManager.saveHotbar) {
                                net.minecraft.item.Item[] pref = new net.minecraft.item.Item[9];
                                for (int i = 0; i < 9; i++)
                                    pref[i] = player.inventory.getStack(i).getItem();
                                stateManager.hotbarPreferences.put(player.getUuid(), pref);
                            }
                            // Pre-set position immediately so the client never renders the old
                            // spawn
                            // location. Only applicable when staying in the same dimension; cross-
                            // dimension joins go through a loading screen so there's no visible
                            // flash.
                            if (server.getWorld(cs.worldKey) == player.getServerWorld()) {
                                player.refreshPositionAndAngles(cs.x, cs.y, cs.z, cs.yaw, cs.pitch);
                            }
                            pendingRestore.put(player.getUuid(), cs);
                            restoreCountdown.put(player.getUuid(), RESTORE_DELAY_TICKS);
                        }
                    }
                });

        // Tick-Handler: Restore-Delay, End-Portal-Erkennung, State-Save, Disconnect-Tracking
        ServerTickEvents.END_SERVER_TICK.register(
                srv -> {
                    // Skip all tick processing if frozen
                    if (frozen) {
                        return;
                    }

                    // ── Deferred state restore ─────────────────────────────────────
                    for (Iterator<Map.Entry<UUID, Integer>> it =
                                    restoreCountdown.entrySet().iterator();
                            it.hasNext(); ) {
                        Map.Entry<UUID, Integer> entry = it.next();
                        int remaining = entry.getValue() - 1;
                        if (remaining <= 0) {
                            it.remove();
                            UUID uuid = entry.getKey();
                            PlayerState s = pendingRestore.remove(uuid);
                            ServerPlayerEntity p = srv.getPlayerManager().getPlayer(uuid);
                            if (p != null && s != null) stateManager.restoreState(p, s);
                        } else {
                            entry.setValue(remaining);
                        }
                    }

                    // ── Remove join invincibility (for N ticks after restoreState) ──────────────
                    stateManager.tickClearRegen(srv);

                    // ── Periodic state save (1 s) + Spectator detection + disconnect tracking
                    // ─────
                    stateSaveTick++;
                    if (stateSaveTick >= 20) {
                        stateSaveTick = 0;
                        Set<UUID> online = new HashSet<>();
                        for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                            online.add(p.getUuid());
                            GameMode mode = p.interactionManager.getGameMode();
                            GameMode prev = lastKnownGameMode.put(p.getUuid(), mode);
                            // Detect game-mode changes and notify Velocity
                            if (prev != null && prev != mode) {
                                boolean nowSpectator = mode == GameMode.SPECTATOR;
                                sendModeToVelocity(p, nowSpectator);
                            }
                            // Only save living survival players (dead players keep the last living
                            // save).
                            if (mode != GameMode.SPECTATOR && p.getHealth() > 0.0f) {
                                stateManager.saveState(p);
                            }
                        }
                        // Clean up disconnected players
                        // Cleanup locale for disconnected players before retainAll
                        connectedPlayers.stream()
                                .filter(u -> !online.contains(u))
                                .forEach(Lang::removeLocale);
                        connectedPlayers.retainAll(online);
                        lastKnownGameMode.keySet().retainAll(online);
                        restoreCountdown.keySet().retainAll(online);
                        pendingRestore.keySet().retainAll(online);
                        pendingSpectators.retainAll(online);
                        stateManager.cleanupDisconnected(online);
                        currentlyDead.retainAll(online);
                        spectatorCameras.keySet().retainAll(online);

                        // Re-lock spectator cameras every second so the watcher cannot escape POV
                        for (Map.Entry<UUID, UUID> cam : spectatorCameras.entrySet()) {
                            ServerPlayerEntity watcher =
                                    srv.getPlayerManager().getPlayer(cam.getKey());
                            ServerPlayerEntity target =
                                    srv.getPlayerManager().getPlayer(cam.getValue());
                            if (watcher == null) continue;
                            if (target == null) {
                                // Target left – try to find any active survival player
                                target = findActiveSurvivalPlayer(srv, cam.getKey());
                                if (target == null) continue;
                                cam.setValue(target.getUuid());
                            }
                            watcher.networkHandler.sendPacket(new SetCameraEntityS2CPacket(target));
                        }
                    }

                    // ── Death detection: clear saved inventory on first tick of death ──────────
                    for (ServerPlayerEntity player : srv.getPlayerManager().getPlayerList()) {
                        if (player.interactionManager.getGameMode() == GameMode.SPECTATOR) continue;
                        UUID uuid = player.getUuid();
                        if (player.getHealth() <= 0.0f) {
                            if (!currentlyDead.contains(uuid)) {
                                currentlyDead.add(uuid);
                                stateManager.clearInventory();
                            }
                        } else {
                            currentlyDead.remove(uuid);
                        }
                    }
                });
    }

    // =========================
    // PORTAL DETECTION (game objective)
    // =========================

    /** Called by EndPortalMixin when a living survival player steps into the End exit portal. */
    public static void onEndPortalEntered(ServerPlayerEntity player) {
        if (INSTANCE != null) {
            INSTANCE.onPlayerExitEnd(player);
        }
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

    /**
     * Send a global message to Velocity (not tied to a specific player). Uses the first available
     * player as transport, or logs a warning if no players are online.
     */
    private void sendGlobalMessageToVelocity(String type, Consumer<ByteArrayDataOutput> writer) {
        if (server == null || server.getPlayerManager().getPlayerList().isEmpty()) {
            System.out.println(
                    "[MCSRSWAP] Cannot send '" + type + "' to Velocity: no players online");
            return;
        }
        // Use first available player as message transport
        ServerPlayerEntity anyPlayer = server.getPlayerManager().getPlayerList().get(0);
        sendToVelocity(anyPlayer, writer);
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
                frozen = true; // Re-freeze after reset
                return; // resetWorldState already calls updateScoreboard()

            case "save":
                // Triggered by Velocity 50 ms before rotation – immediate state save
                // so the state is as up to date as possible for the next player.
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p.interactionManager.getGameMode() != GameMode.SPECTATOR
                            && p.getHealth() > 0.0f) {
                        stateManager.saveState(p);
                    }
                }
                return;

            case "savehotbar":
                stateManager.saveHotbar = in.readBoolean();
                return;

            case "eyehoverticks":
                ModConfig.eyeHoverTicks = in.readInt();
                return;

            case "incoming_spectator":
                {
                    // Pre-notification from Velocity: this player will connect as a watcher
                    // shortly.
                    // Mark them so ENTITY_LOAD skips the normal state restore.
                    pendingSpectators.add(UUID.fromString(in.readUTF()));
                    return;
                }

            case "become_spectator":
                {
                    UUID uuid = UUID.fromString(in.readUTF());
                    // Cancel any pending state restore – the player should become a spectator,
                    // not inherit the current server's active player state.
                    restoreCountdown.remove(uuid);
                    pendingRestore.remove(uuid);
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(uuid);
                    if (target == null) return;
                    // Switch to spectator and lock camera onto the active survival player
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
                    // Teleport far above to clear spectator rendering, switch to survival.
                    // Velocity will connect this player to their real server ~4 s later.
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
    // RESET (Spielstart)
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
