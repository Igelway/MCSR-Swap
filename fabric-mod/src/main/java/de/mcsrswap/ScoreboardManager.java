package de.mcsrswap;

import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ScoreboardManager {

    MinecraftServer server;
    ScoreboardObjective objective;

    void setupScoreboard(MinecraftServer server) {
        this.server = server;
        ServerScoreboard scoreboard = server.getScoreboard();

        // Use getNullableObjective instead of containsObjective (containsObjective is missing in 1.16.1 intermediary)
        ScoreboardObjective existing = scoreboard.getNullableObjective("swap");
        if (existing != null) {
            scoreboard.removeObjective(existing);
        }

        objective = scoreboard.addObjective(
                "swap",
                ScoreboardCriterion.DUMMY,
                new LiteralText("§aSpeedrun"),
                ScoreboardCriterion.RenderType.INTEGER
        );

        // Slot 1 = Sidebar
        scoreboard.setObjectiveSlot(1, objective);
    }

    /** Update the scoreboard and ActionBar for a specific player (on join). */
    void update(boolean finished, int completedWorlds, int requiredWorlds, int currentTime,
                ServerPlayerEntity triggerPlayer) {
        if (objective == null || server == null) return;

        ServerScoreboard scoreboard = server.getScoreboard();

        // Clear old entries
        Collection<ScoreboardPlayerScore> scores = scoreboard.getAllPlayerScores(objective);
        new ArrayList<>(scores).forEach(score ->
                scoreboard.resetPlayerScore(score.getPlayerName(), objective)
        );

        // Show scoreboard entries only when the world goal is met (in the trigger player's language)
        if (finished) {
            UUID lang = triggerPlayer != null ? triggerPlayer.getUuid() : null;
            scoreboard.getPlayerScore(
                    Lang.scoreProgress(lang, completedWorlds, requiredWorlds), objective
            ).setScore(2);
            scoreboard.getPlayerScore(Lang.scoreGoal(lang), objective).setScore(1);
        }

        // ActionBar timer for all players (each in their own language)
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.networkHandler.sendPacket(new TitleS2CPacket(
                    TitleS2CPacket.Action.ACTIONBAR,
                    new LiteralText(Lang.timer(p.getUuid(), currentTime))
            ));
        }
    }

    /** Broadcast variant: no trigger player; uses the first online player as the language reference. */
    void update(boolean finished, int completedWorlds, int requiredWorlds, int currentTime) {
        List<ServerPlayerEntity> players = server != null
                ? server.getPlayerManager().getPlayerList() : Collections.emptyList();
        update(finished, completedWorlds, requiredWorlds, currentTime,
                players.isEmpty() ? null : players.get(0));
    }
}
