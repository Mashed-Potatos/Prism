package dev.prism.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-subserver overrides parsed from the {@code subservers:} block in
 * {@code prism.yml}. Any field left at its default is inherited from the global
 * defaults declared here.
 */
public final class SubserverConfig {
    public Integer port;
    public String jar = "server.jar";
    public List<String> javaArgs = new ArrayList<>(List.of("-Xmx2G", "-Xms1G"));
    public List<String> jarArgs = new ArrayList<>(List.of("nogui"));

    static SubserverConfig from(Map<String, Object> raw) {
        SubserverConfig g = new SubserverConfig();
        if (raw.get("port") instanceof Number n) g.port = n.intValue();
        if (raw.get("jar") instanceof String s) g.jar = s;
        if (raw.get("java-args") instanceof List<?> l) {
            g.javaArgs = new ArrayList<>();
            for (Object o : l) g.javaArgs.add(String.valueOf(o));
        }
        if (raw.get("jar-args") instanceof List<?> l) {
            g.jarArgs = new ArrayList<>();
            for (Object o : l) g.jarArgs.add(String.valueOf(o));
        }
        return g;
    }
}
