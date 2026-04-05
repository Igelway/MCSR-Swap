package de.mcsrswap;

public enum GameState {
    LOBBY, // Players in lobby, no game running
    STARTING, // Docker servers starting, waiting for health checks
    RUNNING // Game is active, timer running
}
