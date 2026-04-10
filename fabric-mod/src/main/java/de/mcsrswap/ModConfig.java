package de.mcsrswap;

import java.util.UUID;

/**
 * Holds runtime configuration values pushed from the Velocity proxy at game start. Defaults apply
 * until the first game is started.
 */
public class ModConfig {

    /**
     * Total lifetime of a thrown Eye of Ender in ticks (vanilla default: 80). Configured via {@code
     * eyeHoverTicks} in the Velocity {@code config.yml}; overwritten via the {@code eyehoverticks}
     * plugin message on each game start.
     */
    public static int eyeHoverTicks = 80;

    /**
     * Fixed UUID used as the filename for this server's player-data slot. All players who play on
     * this server share one {@code <slotUuid>.dat} file, so each incoming player automatically
     * inherits the full state (inventory, XP, position, effects, …) of their predecessor. Set via
     * the {@code slot_uuid} plugin message at game start; {@code null} means no redirect (vanilla
     * per-player files).
     */
    public static UUID slotUuid = null;
}
