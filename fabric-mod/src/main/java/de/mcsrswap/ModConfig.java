package de.mcsrswap;

/**
 * Holds runtime configuration values pushed from the Velocity proxy at game start.
 * Defaults apply until the first game is started.
 */
public class ModConfig {

    /**
     * Total lifetime of a thrown Eye of Ender in ticks (vanilla default: 80).
     * Configured via {@code eyeHoverTicks} in the Velocity {@code config.yml};
     * overwritten via the {@code eyehoverticks} plugin message on each game start.
     */
    public static int eyeHoverTicks = 80;
}
