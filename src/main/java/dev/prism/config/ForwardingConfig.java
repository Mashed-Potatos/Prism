package dev.prism.config;

/**
 * Configuration for how Prism conveys the player's verified identity to backend
 * subservers. Backends run in offline-mode; the forwarding mode controls how the
 * real UUID and Mojang profile properties are delivered.
 */
public final class ForwardingConfig {

    /** Identity-forwarding strategy negotiated with the backend subserver. */
    public enum Mode {
        /** No forwarding: backends see the offline-UUID derived from the verified username. */
        NONE,
        /** Velocity modern: HMAC-SHA256 signed payload carrying real UUID and properties. */
        VELOCITY_MODERN,
        /** BungeeCord legacy: append /ip/uuid/properties to the handshake address. */
        BUNGEECORD
    }

    public Mode mode = Mode.VELOCITY_MODERN;

    /**
     * Velocity-modern shared secret. Must match the value of
     * {@code proxies.velocity.secret} (or {@code velocity-support.secret} on older Paper)
     * in each subserver's {@code paper-global.yml}. Empty string disables modern forwarding.
     */
    public String velocitySecret = "";
}
