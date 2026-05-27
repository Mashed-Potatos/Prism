package dev.prism.protocol;

/**
 * The five Minecraft protocol states. Every Netty pipeline in Prism keeps track of
 * its own state so it can interpret each incoming packet id against the correct table.
 */
public enum ConnectionState {
    /** Initial state until the client sends its handshake packet. */
    HANDSHAKE,
    /** Server List Ping flow. */
    STATUS,
    /** Login flow, including the optional encryption + Mojang authentication handshake. */
    LOGIN,
    /** Configuration-state — registries, brand info, etc. Also re-entered during seamless transfers. */
    CONFIGURATION,
    /** Normal in-world gameplay state. */
    PLAY
}
