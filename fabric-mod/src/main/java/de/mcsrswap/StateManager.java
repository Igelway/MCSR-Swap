package de.mcsrswap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

public class StateManager {

    boolean saveHotbar = true;

    /**
     * UUID of the survival player whose data was last saved to the slot file. Used at ENTITY_LOAD
     * to transfer mob anger from this UUID to the incoming player's UUID.
     */
    UUID lastPlayerUuid = null;

    /**
     * Per-player hotbar layout remembered across rotations. Key = real player UUID; value = array
     * of 9 item types (slots 0–8) as they were arranged the last time the player left a server.
     */
    final Map<UUID, Item[]> hotbarPreferences = new HashMap<>();

    final Map<UUID, Integer> clearRegenAfter = new HashMap<>();

    /** Saves the player's current hotbar layout as their personal preference. */
    void captureHotbarPreference(ServerPlayerEntity p) {
        Item[] pref = new Item[9];
        for (int i = 0; i < 9; i++) pref[i] = p.inventory.getStack(i).getItem();
        hotbarPreferences.put(p.getUuid(), pref);
    }

    /**
     * Rearranges the player's hotbar (slots 0–8) to match their stored personal preference. Items
     * are swapped within the hotbar only; the rest of the inventory is never touched.
     */
    void applyHotbarPreference(ServerPlayerEntity p) {
        Item[] pref = hotbarPreferences.get(p.getUuid());
        if (pref == null) return;
        for (int targetSlot = 0; targetSlot < 9; targetSlot++) {
            Item target = pref[targetSlot];
            if (target == null || target == Items.AIR) continue;
            if (p.inventory.getStack(targetSlot).getItem() == target) continue;
            for (int srcSlot = 0; srcSlot < 9; srcSlot++) {
                if (srcSlot == targetSlot) continue;
                if (p.inventory.getStack(srcSlot).getItem() == target) {
                    ItemStack tmp = p.inventory.getStack(targetSlot);
                    p.inventory.setStack(targetSlot, p.inventory.getStack(srcSlot));
                    p.inventory.setStack(srcSlot, tmp);
                    break;
                }
            }
        }
    }

    /**
     * Remaps all ANGRY_AT brain memories within a 50-block radius (fromUuid → toUuid). Covers
     * Piglins, Bees, and any other mob that uses this memory type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void transferMobAnger(
            ServerWorld world, UUID fromUuid, UUID toUuid, double x, double y, double z) {
        Box area = new Box(x - 50, y - 50, z - 50, x + 50, y + 50, z + 50);
        world.getEntities((Entity) null, area, e -> e instanceof MobEntity)
                .forEach(
                        entity -> {
                            MobEntity mob = (MobEntity) entity;
                            Map<?, Optional<?>> memories =
                                    ((de.mcsrswap.mixin.BrainMemoriesAccessor) mob.getBrain())
                                            .getMemories();
                            Optional<?> memOpt = memories.get(MemoryModuleType.ANGRY_AT);
                            if (memOpt == null || !memOpt.isPresent()) return;

                            net.minecraft.entity.ai.brain.Memory<?> mem =
                                    (net.minecraft.entity.ai.brain.Memory<?>) memOpt.get();
                            if (!fromUuid.equals(mem.getValue())) return;

                            long ttl = ((de.mcsrswap.mixin.MemoryExpiryAccessor) mem).getExpiry();
                            mob.getBrain().remember(MemoryModuleType.ANGRY_AT, toUuid, ttl);
                        });
    }

    /** Iterate clearRegenAfter, decrement, clear timeUntilRegen each tick. */
    void tickClearRegen(MinecraftServer srv) {
        for (Iterator<Map.Entry<UUID, Integer>> it = clearRegenAfter.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            ServerPlayerEntity p = srv.getPlayerManager().getPlayer(entry.getKey());
            if (p != null) p.timeUntilRegen = 0;
            if (remaining <= 0) it.remove();
            else entry.setValue(remaining);
        }
    }

    void cleanupDisconnected(Set<UUID> online) {
        clearRegenAfter.keySet().retainAll(online);
        // hotbarPreferences are intentionally NOT pruned here: a player's preference must
        // survive the brief offline gap during a swap so it can be applied when the next
        // player (or the same player on the next round) connects to this slot.
    }

    void clearHotbarPreferences() {
        hotbarPreferences.clear();
    }
}
