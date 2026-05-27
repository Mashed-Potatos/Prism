package dev.prism.config;

/**
 * Configuration for the built-in admin web panel. All fields map to the
 * {@code panel:} block in {@code prism.yml}.
 */
public final class PanelConfig {
    public boolean enabled = true;
    public String bind = "127.0.0.1";
    public int port = 8080;
    public String username = "admin";
    /** Plaintext. Empty triggers first-boot auto-generation; the generated value is
     *  printed to the console and written back into prism.yml. */
    public String password = "";
}
