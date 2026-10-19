package de.mcsrswap;

import com.velocitypowered.api.proxy.Player;
import java.util.Set;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeEqualityPredicate;
import org.slf4j.Logger;

/**
 * All LuckPerms API references are isolated here. The main plugin checks that LuckPerms is loaded
 * before touching this class, so the plugin also starts on servers without LuckPerms.
 */
final class LuckPermsHook {

    private LuckPermsHook() {}

    static void setupAdmins(Logger logger, Set<String> admins) {
        try {
            LuckPerms api = LuckPermsProvider.get();

            for (String playerName : admins) {
                try {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(playerName);
                    } catch (IllegalArgumentException e) {
                        uuid = api.getUserManager().lookupUniqueId(playerName).get();
                    }
                    if (uuid == null) {
                        logger.warn("Could not find UUID for admin: {}", playerName);
                        continue;
                    }

                    var user = api.getUserManager().loadUser(uuid).get();
                    if (user == null) {
                        logger.warn("Could not load user data for admin: {}", playerName);
                        continue;
                    }

                    user.data().add(Node.builder("swap.admin").build());
                    api.getUserManager().saveUser(user);
                    logger.info("Granted swap.admin permission to: {} ({})", playerName, uuid);
                } catch (Exception e) {
                    logger.error(
                            "Failed to set admin permissions for {}: {}",
                            playerName,
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to setup admin permissions: {}", e.getMessage());
        }
    }

    static void grantAdmin(Logger logger, Player player) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            var user = api.getUserManager().loadUser(player.getUniqueId()).get();
            if (user == null) return;

            Node node = Node.builder("swap.admin").build();
            if (!user.data().contains(node, NodeEqualityPredicate.IGNORE_EXPIRY_TIME).asBoolean()) {
                user.data().add(node);
                api.getUserManager().saveUser(user);
                logger.info(
                        "Granted swap.admin to {} ({})",
                        player.getUsername(),
                        player.getUniqueId());
            }
        } catch (Exception e) {
            logger.error(
                    "Failed to grant swap.admin to {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
