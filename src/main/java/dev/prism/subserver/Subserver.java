package dev.prism.subserver;

import java.nio.file.Path;

/**
 * Immutable handle to one registered backend Minecraft server: its name, folder,
 * jar path, listening port, and the {@link ServerProcess} that wraps its JVM.
 */
public final class Subserver {

    private final String name;
    private final Path folder;
    private final Path jar;
    private final int port;
    private final ServerProcess process;

    public Subserver(String name, Path folder, Path jar, int port, ServerProcess process) {
        this.name = name;
        this.folder = folder;
        this.jar = jar;
        this.port = port;
        this.process = process;
    }

    public String name() { return name; }
    public Path folder() { return folder; }
    public Path jar() { return jar; }
    public int port() { return port; }
    public ServerProcess process() { return process; }

    public boolean isReady() { return process.isReady(); }

    @Override public String toString() { return "Subserver{" + name + " @ :" + port + "}"; }
}
