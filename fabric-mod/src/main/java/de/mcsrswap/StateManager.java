package de.mcsrswap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class StateManager {

    MinecraftServer server;
    PlayerState currentState = null;
    boolean saveHotbar = true;
    /** Joining player's own hotbar preference captured at ENTITY_LOAD, before restoreState fires. */
    final Map<UUID, Item[]> hotbarPreferences = new HashMap<>();
    final Map<UUID, Integer> clearRegenAfter = new HashMap<>();

    void setServer(MinecraftServer srv) {
        this.server = srv;
    }

    PlayerState getCurrentState() {
        return currentState;
    }

    void setCurrentState(PlayerState s) {
        currentState = s;
    }

    void saveState(ServerPlayerEntity p) {
        PlayerState s = new PlayerState();

        s.ownerUuid    = p.getUuid();

        s.worldKey     = p.world.getRegistryKey();
        s.x            = p.getX();
        s.y            = p.getY();
        s.z            = p.getZ();
        s.yaw          = p.yaw;
        s.pitch        = p.pitch;

        s.inventory    = p.inventory.serialize(new ListTag());
        s.enderChest   = serializeInventory(p.getEnderChestInventory());

        s.health       = p.getHealth();
        s.food         = p.getHungerManager().getFoodLevel();
        s.saturation   = p.getHungerManager().getSaturationLevel();

        s.expLevel     = p.experienceLevel;
        s.expProgress  = p.experienceProgress;
        s.totalExperience = p.totalExperience;

        s.fireTicks    = p.getFireTicks();
        s.fallDistance = p.fallDistance;
        s.netherPortalCooldown = p.netherPortalCooldown;
        s.swimming     = p.isSwimming();
        s.fallFlying   = p.isFallFlying();
        s.vehicleUuid  = (p.hasVehicle() && p.getVehicle() != null) ? p.getVehicle().getUuid() : null;
        // Eject the player immediately so the vehicle stays in the world after disconnect.
        // This ensures the UUID lookup in restoreState will succeed for the next player.
        if (s.vehicleUuid != null) p.stopRiding();

        s.statusEffects = new ArrayList<>();
        for (StatusEffectInstance effect : p.getStatusEffects()) {
            s.statusEffects.add(new StatusEffectInstance(
                    effect.getEffectType(),
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.shouldShowParticles()
            ));
        }

        // Copy spawn point (bed / respawn anchor)
        s.spawnPointPosition  = p.getSpawnPointPosition();   // null if no custom spawn
        s.spawnPointDimension = p.getSpawnPointDimension();
        // isSpawnForced() does not exist in 1.16.1 Yarn; forced is always false in practice
        s.spawnForced = false;

        currentState = s;
    }

    void restoreState(ServerPlayerEntity p, PlayerState s) {
        // Dismount any vehicle the incoming player might be riding before teleport
        p.stopRiding();

        // Pre-load the chunk at the target position (prevents "left loaded chunk area")
        ServerWorld targetWorld = server.getWorld(s.worldKey);
        if (targetWorld != null) {
            targetWorld.getChunk((int) s.x >> 4, (int) s.z >> 4);
            p.teleport(targetWorld, s.x, s.y, s.z, s.yaw, s.pitch);
        }

        // Transfer mob aggro: everything that was angry at the old player now targets the new one
        if (targetWorld != null && s.ownerUuid != null && !s.ownerUuid.equals(p.getUuid())) {
            transferMobAnger(targetWorld, s.ownerUuid, p.getUuid(), s.x, s.y, s.z);
        }

        // Re-mount the vehicle the previous player was riding (boat, minecart, horse, …)
        if (s.vehicleUuid != null && targetWorld != null) {
            net.minecraft.entity.Entity vehicle = targetWorld.getEntity(s.vehicleUuid);
            if (vehicle != null) {
                p.startRiding(vehicle, true);
            }
        }

        // Inventory
        p.inventory.clear();
        p.inventory.deserialize(s.inventory);
        if (saveHotbar) {
            Item[] pref = hotbarPreferences.remove(p.getUuid());
            if (pref != null) rearrangeHotbar(p, pref);
        }

        // Ender chest
        p.getEnderChestInventory().clear();
        if (s.enderChest != null) {
            deserializeInventory(s.enderChest, p.getEnderChestInventory());
        }

        // Vital stats
        p.setHealth(Math.min(s.health, p.getMaxHealth()));
        p.getHungerManager().setFoodLevel(s.food);
        ((de.mcsrswap.mixin.HungerManagerAccessor) p.getHungerManager()).setSaturationLevel(s.saturation);

        // XP – restore all three fields before sending the packet to avoid
        // the server's own join-sequence packet overriding us with stale data.
        p.experienceLevel    = s.expLevel;
        p.experienceProgress = s.expProgress;
        p.totalExperience    = s.totalExperience;
        // Vanilla auto-sync calls: new ExperienceBarUpdateS2CPacket(expProgress, totalExperience, expLevel)
        // The constructor is (float barProgress, int experienceLevel, int experience) but the wire
        // and client read order swaps the last two: experience (2nd on wire) → displayed level,
        // experienceLevel (3rd on wire) → raw XP total. Mirror vanilla's order exactly.
        p.networkHandler.sendPacket(
                new ExperienceBarUpdateS2CPacket(s.expProgress, s.totalExperience, s.expLevel)
        );

        // Fire: restore from saved ticks; extinguish if not on fire
        if (s.fireTicks > 0) {
            p.setOnFireFor((s.fireTicks + 19) / 20);
        } else {
            p.extinguish();
        }
        p.fallDistance = 0f;
        p.netherPortalCooldown = s.netherPortalCooldown;

        // Status effects
        new ArrayList<>(p.getStatusEffects())
                .forEach(effect -> p.removeStatusEffect(effect.getEffectType()));
        s.statusEffects.forEach(p::addStatusEffect);

        // Restore swimming/crouching pose (also covers boat/trapdoor glitch)
        p.setSwimming(s.swimming);

        // Restore elytra flight (entity flag 7 = FALL_FLYING)
        ((de.mcsrswap.mixin.EntityFlagInvoker) p).invokeSetFlag(7, s.fallFlying);

        // Game mode: always force SURVIVAL (no Creative intermediate state)
        p.interactionManager.setGameMode(GameMode.SURVIVAL, p.interactionManager.getGameMode());

        // Adopt the previous player's spawn point.
        // If spawnPointPosition is null, the spawn is cleared → Vanilla uses the world spawn.
        p.setSpawnPoint(
                s.spawnPointDimension != null ? s.spawnPointDimension : World.OVERWORLD,
                s.spawnPointPosition,   // null = no custom spawn
                s.spawnForced,
                false                   // no chat feedback
        );

        // Disable join invincibility – immediately and for the next 20 ticks,
        // so that Purpur or other mods don't reset the invincibility after our clear.
        p.timeUntilRegen = 0;
        clearRegenAfter.put(p.getUuid(), 20);
    }

    /**
     * Remaps all ANGRY_AT brain memories within a 50-block radius (fromUuid → toUuid).
     * Covers Piglins, Bees, and any other mob that uses this memory type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void transferMobAnger(ServerWorld world, UUID fromUuid, UUID toUuid,
                           double x, double y, double z) {
        Box area = new Box(x - 50, y - 50, z - 50, x + 50, y + 50, z + 50);
        world.getEntities((Entity) null, area, e -> e instanceof MobEntity).forEach(entity -> {
            MobEntity mob = (MobEntity) entity;
            // Get the raw Memory object (including TTL) instead of just the value
            Map<?, Optional<?>> memories = ((de.mcsrswap.mixin.BrainMemoriesAccessor) mob.getBrain()).getMemories();
            Optional<?> memOpt = memories.get(MemoryModuleType.ANGRY_AT);
            if (memOpt == null || !memOpt.isPresent()) return;

            net.minecraft.entity.ai.brain.Memory<?> mem =
                    (net.minecraft.entity.ai.brain.Memory<?>) memOpt.get();
            if (!fromUuid.equals(mem.getValue())) return;

            // Preserve the original TTL; 0 means "no expiry" – keep it as 0
            long ttl = ((de.mcsrswap.mixin.MemoryExpiryAccessor) mem).getExpiry();
            mob.getBrain().remember(MemoryModuleType.ANGRY_AT, toUuid, ttl);
        });
    }

    /** Iterate clearRegenAfter, decrement, clear timeUntilRegen each tick. */
    void tickClearRegen(MinecraftServer srv) {
        for (Iterator<Map.Entry<UUID, Integer>> it = clearRegenAfter.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            ServerPlayerEntity p = srv.getPlayerManager().getPlayer(entry.getKey());
            if (p != null) p.timeUntilRegen = 0;
            if (remaining <= 0) it.remove();
            else entry.setValue(remaining);
        }
    }

    /**
     * Rearranges the new player's hotbar (slots 0-8) according to the joining player's
     * own preferred layout, captured just before restoreState from their own server NBT.
     * For each preferred slot, we scan the predecessor's hotbar for a matching item type
     * and swap it into place. Only hotbar slots are ever touched.
     */
    private static void rearrangeHotbar(ServerPlayerEntity p, Item[] preference) {
        for (int targetSlot = 0; targetSlot < 9; targetSlot++) {
            Item target = preference[targetSlot];
            if (target == null || target == Items.AIR) continue;
            if (p.inventory.getStack(targetSlot).getItem() == target) continue; // already in place

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

    private static ListTag serializeInventory(Inventory inv) {
        ListTag tag = new ListTag();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = stack.toTag(new CompoundTag());
                entry.putByte("Slot", (byte) i);
                tag.add(entry);
            }
        }
        return tag;
    }

    private static void deserializeInventory(ListTag tag, Inventory inv) {
        for (int i = 0; i < tag.size(); i++) {
            CompoundTag entry = tag.getCompound(i);
            int slot = entry.getByte("Slot") & 0xFF;
            if (slot < inv.size()) {
                inv.setStack(slot, ItemStack.fromTag(entry));
            }
        }
    }

    void cleanupDisconnected(Set<UUID> online) {
        clearRegenAfter.keySet().retainAll(online);
        hotbarPreferences.keySet().retainAll(online);
    }

    /** Clears the saved inventory and XP so the next player inherits an empty slot list and
     *  zero experience. Called when the active player dies; their items remain as dropped
     *  entities in the world. */
    void clearInventory() {
        if (currentState == null) return;
        currentState.inventory = new ListTag();
        currentState.enderChest = new ListTag();
        currentState.expLevel    = 0;
        currentState.expProgress = 0.0f;
        currentState.totalExperience = 0;
    }
}
