package dev.prism.subserver;

import dev.prism.config.ForwardingConfig;
import dev.prism.config.PrismConfig;
import dev.prism.config.SubserverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers subserver folders under the Prism root and launches one JVM per subserver.
 *
 * A subserver is any directory directly under the Prism root that contains a
 * {@code server.jar}. The folder name is the subserver name. All subservers are
 * peers — they own their own plugins, configs, and worlds. Use the console
 * {@code sync} command to copy plugins between them on demand.
 *
 * Prism does NOT download any server jars. If a candidate folder lacks a server.jar
 * Prism logs a warning telling the admin to drop one in, and skips that folder.
 */
public final class SubserverManager {

    private static final Logger log = LoggerFactory.getLogger(SubserverManager.class);

    private static final Set<String> RESERVED_FOLDERS = Set.of(".prism-keys");

    // Free version limited to 2 subservers. Purchase the unlimited version at
    // builtbybit.com for no limit.
    public static final int MAX_SUBSERVERS =2;

    private final Path root;
    private final PrismConfig config;
    private final Map<String, Subserver> subservers = new ConcurrentHashMap<>();
    private final List<String> orderedNames = new ArrayList<>();

    public SubserverManager(Path root, PrismConfig config) {
        this.root = root;
        this.config = config;
    }

    public void discoverAndLaunch(Set<Integer> alreadyUsedPorts) throws IOException {
        Set<Integer> usedPorts = new HashSet<>(alreadyUsedPorts);
        usedPorts.add(config.bindPort);

        List<Path> candidates = listCandidateFolders();

        if (!hasAnyServerJar(candidates)) {
            bootstrapDefaultFolders();
            candidates = listCandidateFolders();
        }

        int nextAutoPort = config.basePort;

        int skippedDueToTrial = 0;
        for (Path folder : candidates) {
            String name = folder.getFileName().toString();
            SubserverConfig sc = config.subserverOverrides.getOrDefault(name, new SubserverConfig());
            Path jar = folder.resolve(sc.jar);
            if (!Files.isRegularFile(jar)) {
                log.warn("Subserver folder '{}' has no {} — drop a server.jar into {} and restart. Skipping.",
                        name, sc.jar, folder);
                continue;
            }
            if (subservers.size() >= MAX_SUBSERVERS) {
                skippedDueToTrial++;
                continue;
            }

            int port = sc.port != null ? sc.port : nextAutoPort;
            while (usedPorts.contains(port)) port++;
            usedPorts.add(port);
            if (sc.port == null) nextAutoPort = port + 1;

            patchPaperGlobalYml(folder, name);

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExecutable());
            cmd.addAll(sc.javaArgs);
            cmd.add("-jar");
            cmd.add(jar.getFileName().toString());
            cmd.addAll(sc.jarArgs);

            ServerProcess proc = new ServerProcess(name, folder, cmd, port);
            proc.prepare(Map.of());
            proc.start();

            Subserver s = new Subserver(name, folder, jar, port, proc);
            subservers.put(name, s);
            orderedNames.add(name);
            log.info("Registered subserver {}", s);
        }

        if (subservers.isEmpty()) {
            log.warn("No subserver folders with a server.jar were found under {}. Create a folder " +
                    "under {} and drop a server.jar inside it to register a subserver.", root, root);
        }
        if (skippedDueToTrial > 0) {
            log.warn("Trial version: skipped {} additional subserver(s). Cap is {}.",
                    skippedDueToTrial, MAX_SUBSERVERS);
        }
    }

    private boolean hasAnyServerJar(List<Path> candidates) {
        for (Path folder : candidates) {
            String name = folder.getFileName().toString();
            SubserverConfig sc = config.subserverOverrides.getOrDefault(name, new SubserverConfig());
            if (Files.isRegularFile(folder.resolve(sc.jar))) return true;
        }
        return false;
    }

    private void bootstrapDefaultFolders() {
        String[] defaults = {"server1", "server2"};
        List<String> created = new ArrayList<>();
        for (String name : defaults) {
            Path folder = root.resolve(name);
            try {
                Files.createDirectories(folder.resolve("plugins"));
                created.add(name);
            } catch (IOException e) {
                log.warn("Failed to create default subserver folder '{}': {}", name, e.toString());
            }
        }
        if (!created.isEmpty()) {
            log.info("First-boot setup: no subserver folders with a server.jar were found.");
            log.info("Created default subserver folder(s) under {}: {}", root, String.join(", ", created));
            for (String name : created) {
                log.info("  - {} (with empty plugins/ folder)", root.resolve(name));
            }
            log.info("ACTION REQUIRED: drop a server.jar into each of the above folders, then start Prism again.");
        }
    }

    private List<Path> listCandidateFolders() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                String fname = p.getFileName().toString();
                if (fname.startsWith(".")) continue;
                if (RESERVED_FOLDERS.contains(fname)) continue;
                out.add(p);
            }
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    // ---------------- lifecycle ----------------

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path candidate = Path.of(home, "bin", exe);
        return Files.isExecutable(candidate) ? candidate.toString() : "java";
    }

    public Optional<Subserver> get(String name) { return Optional.ofNullable(subservers.get(name)); }

    public Collection<Subserver> all() { return subservers.values(); }

    /** Number of registered subservers whose process is currently alive. */
    public int runningCount() {
        int n = 0;
        for (Subserver s : subservers.values()) {
            if (s.process().isAlive()) n++;
        }
        return n;
    }

    /** Returns the name of the first registered subserver, if any. Used by callers that
     *  need a sensible default context (e.g. the console) when no explicit configuration
     *  picks one. */
    public Optional<String> firstName() {
        return orderedNames.stream().filter(subservers::containsKey).findFirst();
    }

    public Optional<Subserver> defaultSubserver() {
        if (config.defaultSubserver != null) {
            Subserver s = subservers.get(config.defaultSubserver);
            if (s != null) return Optional.of(s);
        }
        for (String n : orderedNames) {
            Subserver s = subservers.get(n);
            if (s != null) return Optional.of(s);
        }
        return Optional.empty();
    }

    public void shutdownAll() {
        for (Subserver s : subservers.values()) {
            try { s.process().stop(); } catch (Exception e) { log.warn("Stop {} failed: {}", s.name(), e.toString()); }
        }
    }

    public boolean stop(String name) {
        Subserver s = subservers.get(name);
        if (s == null) return false;
        s.process().stop();
        return true;
    }

    public boolean start(String name) {
        Subserver s = subservers.get(name);
        if (s == null) return false;
        try {
            patchPaperGlobalYml(s.folder(), name);
            s.process().start();
            return true;
        } catch (IOException e) {
            log.warn("Start {} failed: {}", name, e.toString());
            return false;
        }
    }

    /**
     * Stamps paper-global.yml with the velocity-modern secret so Paper trusts Prism's
     * forwarded identity. Skipped silently if forwarding isn't VELOCITY_MODERN. If
     * patching fails we log a loud warning but still launch — players might still
     * connect via the legacy/none paths.
     */
    private void patchPaperGlobalYml(Path folder, String name) {
        if (config.forwarding.mode != ForwardingConfig.Mode.VELOCITY_MODERN) return;
        String secret = config.forwarding.velocitySecret;
        if (secret == null || secret.isEmpty()) {
            log.warn("[{}] velocity-modern forwarding selected but velocity-secret is empty — " +
                    "skipping paper-global.yml patch. Backend plugins may intercept PlayerLoginEvent.", name);
            return;
        }
        try {
            PaperGlobalYmlPatcher.enableVelocityModern(folder, secret);
        } catch (IOException e) {
            log.warn("[{}] Failed to patch paper-global.yml: {} — backend may not trust Prism's " +
                    "forwarded identity, plugins may reject the login.", name, e.toString());
        }
    }

    public boolean restart(String name) {
        if (!stop(name)) return false;
        return start(name);
    }
}
