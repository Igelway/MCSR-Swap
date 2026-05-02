package de.mcsrswap;

public enum GameState {
    LOBBY, // Players in lobby, no game running
    STARTING, // Docker servers starting for an immediate game (players will be routed on ready)
    PREPARING, // Docker servers starting for /ms prepare (players NOT routed; goes to READY_CHECK)
    READY_CHECK, // Servers ready; waiting for all participants to confirm readiness
    RUNNING // Game is active, timer running
}
