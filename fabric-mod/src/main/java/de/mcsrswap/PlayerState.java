package de.mcsrswap;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.List;
import java.util.UUID;

public class PlayerState {
    /** UUID of the player who saved this state – used for mob-aggro transfer. */
    public UUID ownerUuid;
    public RegistryKey<World> worldKey;
    public double x, y, z;
    public float yaw, pitch;

    public ListTag inventory;
    public ListTag enderChest;

    public float health;
    public int   food;
    public float saturation;

    public int   expLevel;
    public float expProgress;
    public int   totalExperience;

    public int   fireTicks;
    public float fallDistance;
    public int   netherPortalCooldown;

    public boolean swimming;    // Swimming/crouching pose (including trapdoor/boat glitch)
    public boolean fallFlying;  // Elytra flight

    /** UUID of the entity the player was riding, or null if on foot. */
    public UUID vehicleUuid;

    public List<StatusEffectInstance> statusEffects;

    // Spawn point (bed / respawn anchor).  null = no custom spawn.
    public BlockPos          spawnPointPosition;
    public RegistryKey<World> spawnPointDimension;
    public boolean           spawnForced;
}
