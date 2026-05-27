package dev.prism.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory representation of {@code prism.yml}. Parsed once at boot and treated
 * as immutable thereafter, except for two fields that may be mutated and persisted
 * back to disk on first run: {@link PanelConfig#password} and
 * {@link ForwardingConfig#velocitySecret}.
 *
 * <p>The YAML parser tolerates missing or partially-typed values and falls back to
 * the field defaults declared on this class.
 */
public final class PrismConfig {

    public String bindHost = "0.0.0.0";
    public int bindPort = 25565;
    public String motd = "A Prism Network";
    public int maxPlayers = 100;

    /** Require full Mojang authentication. Default true. False disables encryption + auth. */
    public boolean onlineMode = true;

    /** How Prism passes the verified UUID + properties to backend subservers. */
    public final ForwardingConfig forwarding = new ForwardingConfig();

    /** Web admin panel. */
    public final PanelConfig panel = new PanelConfig();

    public int basePort = 25566;
    public String defaultSubserver;

    public final Map<String, SubserverConfig> subserverOverrides = new HashMap<>();

    /** chat-group-name -> set of subserver names that share chat. Empty = global default. */
    public final Map<String, Set<String>> chatGroups = new LinkedHashMap<>();

    /**
     * Rewrites the {@code panel.password: "..."} line in-place, preserving comments and
     * surrounding YAML structure. Falls back to appending a panel block if no line matches.
     */
    public static void writePanelPassword(Path file, String newPassword) throws IOException {
        String text = Files.readString(file);
        String escaped = newPassword.replace("\\", "\\\\").replace("\"", "\\\"");
        String replacement = "  password: \"" + escaped + "\"";
        String updated = text.replaceFirst("(?m)^\\s*password:\\s*\".*?\"\\s*$", java.util.regex.Matcher.quoteReplacement(replacement));
        if (updated.equals(text)) {
            String sep = text.endsWith("\n") ? "" : "\n";
            updated = text + sep + "\npanel:\n" + replacement + "\n";
        }
        Files.writeString(file, updated);
    }

    /**
     * Rewrites the {@code forwarding.velocity-secret: "..."} line in-place. Falls back to
     * appending a forwarding block if no line matches.
     */
    public static void writeVelocitySecret(Path file, String secret) throws IOException {
        String text = Files.readString(file);
        String escaped = secret.replace("\\", "\\\\").replace("\"", "\\\"");
        String replacement = "  velocity-secret: \"" + escaped + "\"";
        String updated = text.replaceFirst("(?m)^\\s*velocity-secret:\\s*\".*?\"\\s*$", java.util.regex.Matcher.quoteReplacement(replacement));
        if (updated.equals(text)) {
            String sep = text.endsWith("\n") ? "" : "\n";
            updated = text + sep + "\nforwarding:\n  mode: velocity-modern\n" + replacement + "\n";
        }
        Files.writeString(file, updated);
    }

    /**
     * Loads a config from the given path, or seeds it from the bundled
     * {@code prism.yml} resource if no file is present. The returned config
     * always reflects the on-disk state after this call.
     */
    public static PrismConfig loadOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            try (InputStream in = PrismConfig.class.getResourceAsStream("/prism.yml")) {
                if (in == null) throw new IOException("Default prism.yml resource missing");
                Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
                Files.copy(in, file);
            }
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse(new Yaml().load(in));
        }
    }

    @SuppressWarnings("unchecked")
    private static PrismConfig parse(Object root) {
        PrismConfig cfg = new PrismConfig();
        if (!(root instanceof Map<?, ?> m)) return cfg;

        Map<String, Object> proxy = (Map<String, Object>) m.get("proxy");
        if (proxy != null) {
            if (proxy.get("bind") instanceof String s) cfg.bindHost = s;
            if (proxy.get("port") instanceof Number n) cfg.bindPort = n.intValue();
            if (proxy.get("motd") instanceof String s) cfg.motd = s;
            if (proxy.get("max-players") instanceof Number n) cfg.maxPlayers = n.intValue();
            if (proxy.get("online-mode") instanceof Boolean b) cfg.onlineMode = b;
        }

        Map<String, Object> fwd = (Map<String, Object>) m.get("forwarding");
        if (fwd != null) {
            if (fwd.get("mode") instanceof String s) {
                try { cfg.forwarding.mode = ForwardingConfig.Mode.valueOf(s.toUpperCase().replace('-', '_')); }
                catch (IllegalArgumentException e) { /* leave default */ }
            }
            if (fwd.get("velocity-secret") instanceof String s) cfg.forwarding.velocitySecret = s;
        }

        Map<String, Object> subservers = (Map<String, Object>) m.get("subservers");
        if (subservers != null) {
            if (subservers.get("base-port") instanceof Number n) cfg.basePort = n.intValue();
            for (Map.Entry<String, Object> e : subservers.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> raw)) continue;
                if (e.getKey().equals("base-port")) continue;
                cfg.subserverOverrides.put(e.getKey(), SubserverConfig.from((Map<String, Object>) raw));
            }
        }

        if (m.get("default-subserver") instanceof String s) cfg.defaultSubserver = s;

        Map<String, Object> panel = (Map<String, Object>) m.get("panel");
        if (panel != null) {
            if (panel.get("enabled") instanceof Boolean b) cfg.panel.enabled = b;
            if (panel.get("bind") instanceof String s) cfg.panel.bind = s;
            if (panel.get("port") instanceof Number n) cfg.panel.port = n.intValue();
            if (panel.get("username") instanceof String s) cfg.panel.username = s;
            // Coerce to String rather than gating on `instanceof String` — SnakeYAML demotes
            // unquoted scalars to Number/Boolean/null when they look like one (e.g. `password: 12345678`
            // becomes Long, `password: yes` becomes Boolean). Without coercion the field would
            // silently stay at its default "" and login would always reject the value the user
            // sees on disk. We still treat a YAML null as "leave default" so the auto-generation
            // path in Prism.main can take over.
            Object pwd = panel.get("password");
            if (pwd != null) cfg.panel.password = String.valueOf(pwd);
        }

        Object chat = m.get("chat-groups");
        if (chat instanceof Map<?, ?> cm) {
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                if (!(e.getValue() instanceof List<?> list)) continue;
                Set<String> set = new LinkedHashSet<>();
                for (Object o : list) set.add(String.valueOf(o));
                cfg.chatGroups.put(String.valueOf(e.getKey()), set);
            }
        }
        return cfg;
    }

}
