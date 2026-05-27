package dev.prism.subserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Admin-only, on-demand plugin sync from one subserver's plugins/ folder into another's.
 * Triggered exclusively via the console {@code sync <from> <to> <all|PluginName>} command —
 * nothing here runs at startup or on subserver restart.
 *
 * For each plugin in scope, the source jar is copied (overwriting) and the source's config
 * folder (matched case-insensitively against the plugin.yml {@code name:}, falling back to the
 * jar's basename) is copied recursively into the target. Both are overwritten unconditionally.
 */
public final class PluginSync {

    private static final Logger log = LoggerFactory.getLogger(PluginSync.class);

    private PluginSync() {}

    /**
     * @param fromName   Source subserver name (for log prefixes).
     * @param toName     Target subserver name (for log prefixes).
     * @param fromPlugins Source {@code plugins/} folder.
     * @param toPlugins   Target {@code plugins/} folder.
     * @param filter     "all" (case-insensitive) to sync everything, otherwise the plugin name
     *                   (case-insensitive, matched against plugin.yml or jar basename).
     */
    public static void sync(String fromName, String toName, Path fromPlugins, Path toPlugins, String filter) {
        if (!Files.isDirectory(fromPlugins)) {
            log.warn("Sync: source plugins dir does not exist: {}", fromPlugins);
            return;
        }
        try { Files.createDirectories(toPlugins); }
        catch (IOException e) {
            log.warn("Sync: cannot create target plugins dir {}: {}", toPlugins, e.toString());
            return;
        }

        // Build jar -> resolved plugin name index from the source.
        Map<Path, String> jarsByName = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(fromPlugins, "*.jar")) {
            for (Path jar : ds) {
                if (Files.isRegularFile(jar)) jarsByName.put(jar, resolvePluginName(jar));
            }
        } catch (IOException e) {
            log.warn("Sync: cannot list source jars in {}: {}", fromPlugins, e.toString());
            return;
        }

        boolean all = "all".equalsIgnoreCase(filter);
        String wanted = all ? null : filter;
        int jarsCopied = 0, configFolders = 0, configFiles = 0, jarsFailed = 0, configsMissing = 0;
        boolean matched = false;

        for (Map.Entry<Path, String> e : jarsByName.entrySet()) {
            Path jar = e.getKey();
            String pluginName = e.getValue();
            if (!all && !pluginName.equalsIgnoreCase(wanted)) continue;
            matched = true;

            Path destJar = toPlugins.resolve(jar.getFileName());
            try {
                Files.copy(jar, destJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                log.info("Sync [{} -> {}] copied jar  {} -> {}", fromName, toName, jar.getFileName(), destJar);
                jarsCopied++;
            } catch (IOException ex) {
                log.warn("Sync [{} -> {}] failed to copy jar {} -> {}: {}",
                        fromName, toName, jar.getFileName(), destJar, ex.toString());
                jarsFailed++;
                continue;
            }

            Path srcCfg = findConfigDir(fromPlugins, pluginName);
            if (srcCfg == null) {
                log.info("Sync [{} -> {}] skipped config '{}' — no matching folder in source plugins/",
                        fromName, toName, pluginName);
                configsMissing++;
                continue;
            }
            Path dstCfg = toPlugins.resolve(srcCfg.getFileName());
            try {
                int[] sub = copyDirRecursive(srcCfg, dstCfg);
                log.info("Sync [{} -> {}] copied config '{}' ({} file(s)) -> {}",
                        fromName, toName, srcCfg.getFileName(), sub[0], dstCfg);
                configFolders++;
                configFiles += sub[0];
                if (sub[1] > 0) log.warn("Sync [{} -> {}] {} file(s) inside '{}' failed to copy",
                        fromName, toName, sub[1], srcCfg.getFileName());
            } catch (IOException ex) {
                log.warn("Sync [{} -> {}] failed to copy config '{}' -> {}: {}",
                        fromName, toName, srcCfg.getFileName(), dstCfg, ex.toString());
            }
        }

        if (!all && !matched) {
            log.warn("Sync [{} -> {}] no plugin in {} matched '{}'", fromName, toName, fromPlugins, wanted);
            return;
        }

        log.info("Sync [{} -> {}] summary: {} jar(s) copied, {} config folder(s) copied " +
                        "({} file(s) total), {} jar(s) failed, {} config(s) not present in source",
                fromName, toName, jarsCopied, configFolders, configFiles, jarsFailed, configsMissing);
    }

    /** Returns the case-insensitive match of {@code pluginName} as a subdirectory of {@code plugins}, or null. */
    private static Path findConfigDir(Path plugins, String pluginName) {
        String lc = pluginName.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(plugins)) {
            for (Path p : ds) {
                if (Files.isDirectory(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).equals(lc)) {
                    return p;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** Reads {@code plugin.yml}'s {@code name:} field from the jar; falls back to the jar's basename. */
    private static String resolvePluginName(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry e = jf.getJarEntry("plugin.yml");
            if (e == null) e = jf.getJarEntry("paper-plugin.yml");
            if (e != null) {
                try (InputStream in = jf.getInputStream(e)) {
                    Object root = new org.yaml.snakeyaml.Yaml().load(in);
                    if (root instanceof Map<?, ?> m && m.get("name") instanceof String name && !name.isBlank()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        String fn = jar.getFileName().toString();
        return fn.toLowerCase(Locale.ROOT).endsWith(".jar") ? fn.substring(0, fn.length() - 4) : fn;
    }

    /** Returns [filesCopied, filesFailed]. */
    private static int[] copyDirRecursive(Path src, Path dst) throws IOException {
        int copied = 0, failed = 0;
        Files.createDirectories(dst);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            for (Path entry : ds) {
                Path target = dst.resolve(entry.getFileName());
                if (Files.isDirectory(entry)) {
                    int[] sub = copyDirRecursive(entry, target);
                    copied += sub[0]; failed += sub[1];
                } else {
                    try {
                        Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        copied++;
                    } catch (IOException ex) {
                        failed++;
                    }
                }
            }
        }
        return new int[]{copied, failed};
    }
}
