package dev.prism.subserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stamps {@code config/paper-global.yml} with the velocity-modern forwarding settings
 * that Prism requires for backend subservers. Patching happens before the subserver JVM
 * is launched so Paper reads our values during its first config load — this is what
 * makes Paper trust the spoofed UUID/properties Prism sends instead of letting random
 * plugins intercept PlayerLoginEvent and reject the synthetic identity.
 *
 * If the file does not exist (first boot of a fresh subserver), a minimal stub with just
 * the proxies block is written. Paper merges its defaults for everything else on load.
 *
 * If the file does exist, the YAML is parsed, the proxies.velocity branch is updated,
 * and the file is re-dumped. SnakeYAML strips comments on round-trip — this is a known
 * tradeoff vs. attempting line-based patching of a constantly-shifting Paper config
 * schema. Admins who want Paper's commented defaults back can delete the file and let
 * Paper re-generate it (we'll re-patch on next launch).
 */
public final class PaperGlobalYmlPatcher {

    private static final Logger log = LoggerFactory.getLogger(PaperGlobalYmlPatcher.class);

    private PaperGlobalYmlPatcher() {}

    /**
     * Patches {@code <serverFolder>/config/paper-global.yml} to enable velocity-modern
     * forwarding with the given shared secret. Idempotent — safe to call on every launch.
     */
    public static void enableVelocityModern(Path serverFolder, String secret) throws IOException {
        if (secret == null || secret.isEmpty()) {
            throw new IOException("Refusing to write empty velocity-modern secret to paper-global.yml");
        }
        Path file = serverFolder.resolve("config").resolve("paper-global.yml");
        Files.createDirectories(file.getParent());

        Map<String, Object> root = loadRoot(file);
        Map<String, Object> proxies = childMap(root, "proxies");
        Map<String, Object> velocity = childMap(proxies, "velocity");
        velocity.put("enabled", true);
        velocity.put("online-mode", false);
        velocity.put("secret", secret);

        dump(file, root);
        log.info("[{}] paper-global.yml patched: proxies.velocity.enabled=true, secret=<{} chars>",
                serverFolder.getFileName(), secret.length());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadRoot(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (loaded instanceof Map<?, ?> m) {
                return new LinkedHashMap<>((Map<String, Object>) m);
            }
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map<?, ?> m) {
            // Preserve insertion order while letting us mutate freely.
            Map<String, Object> mut = new LinkedHashMap<>((Map<String, Object>) m);
            parent.put(key, mut);
            return mut;
        }
        Map<String, Object> fresh = new LinkedHashMap<>();
        parent.put(key, fresh);
        return fresh;
    }

    private static void dump(Path file, Map<String, Object> root) throws IOException {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        try (OutputStream os = Files.newOutputStream(file);
             OutputStreamWriter w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            w.write("# Auto-managed by Prism: proxies.velocity is rewritten on every launch.\n");
            w.write("# Other keys are preserved across launches but comments are stripped on patch.\n");
            new Yaml(opts).dump(root, w);
        }
    }
}
