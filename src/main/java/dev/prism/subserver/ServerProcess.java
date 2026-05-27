package dev.prism.subserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Wraps the JVM process that runs a single Minecraft backend (subserver or parent).
 * Stdio is mirrored to the "backend" logger as "[name] <line>"; isReady() flips once
 * we manage to open a TCP connection to the process's port.
 */
public final class ServerProcess {

    private static final Logger backendLog = LoggerFactory.getLogger("backend");
    private final Logger log;
    private final String name;
    private final Path workDir;
    private final List<String> command;
    private final int port;

    private Process process;
    private Thread stdoutPump;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private Thread readinessProbe;

    private static final int RECENT_LINES_CAP = 200;
    private final Deque<String> recentLines = new ArrayDeque<>(RECENT_LINES_CAP);
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Snapshot of the last {@value #RECENT_LINES_CAP} stdout lines (oldest first). */
    public synchronized List<String> recentLines() { return new ArrayList<>(recentLines); }

    /** Subscribe to new stdout lines. Returns the same listener so callers can unsubscribe. */
    public Consumer<String> subscribe(Consumer<String> listener) {
        listeners.add(listener);
        return listener;
    }

    public void unsubscribe(Consumer<String> listener) { listeners.remove(listener); }

    private void pushLine(String line) {
        synchronized (this) {
            recentLines.addLast(line);
            while (recentLines.size() > RECENT_LINES_CAP) recentLines.removeFirst();
        }
        for (Consumer<String> l : listeners) {
            try { l.accept(line); } catch (Exception ignored) {}
        }
    }

    public ServerProcess(String name, Path workDir, List<String> command, int port) {
        this.log = LoggerFactory.getLogger("prism/" + name);
        this.name = name;
        this.workDir = workDir;
        this.command = command;
        this.port = port;
    }

    public String name() { return name; }
    public int port() { return port; }
    public boolean isAlive() { return process != null && process.isAlive(); }
    public boolean isReady() { return ready.get(); }

    /** Send a command line to the backend's stdin (no trailing newline needed). */
    public synchronized void sendCommand(String line) {
        if (process == null || !process.isAlive()) {
            log.warn("Cannot send command, {} not running", name);
            return;
        }
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            log.warn("Failed to write stdin for {}: {}", name, e.toString());
        }
    }

    /** Writes/updates server.properties + eula.txt before launch. Always sets server-port and
     *  online-mode=false. Additional key/value pairs in {@code overrides} are merged in (and
     *  override any existing values for those keys). */
    public void prepare(Map<String, String> overrides) throws IOException {
        Path eula = workDir.resolve("eula.txt");
        if (!Files.exists(eula)) Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8);

        Map<String, String> required = new LinkedHashMap<>();
        required.put("server-port", String.valueOf(port));
        required.put("online-mode", "false");
        if (overrides != null) required.putAll(overrides);

        Path props = workDir.resolve("server.properties");
        List<String> lines = new ArrayList<>();
        if (Files.exists(props)) {
            for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) { lines.add(line); continue; }
                String key = line.substring(0, eq);
                if (required.containsKey(key)) {
                    lines.add(key + "=" + required.remove(key));
                } else {
                    lines.add(line);
                }
            }
        }
        for (Map.Entry<String, String> e : required.entrySet()) {
            lines.add(e.getKey() + "=" + e.getValue());
        }
        Files.write(props, lines);
    }

    public synchronized void start() throws IOException {
        if (process != null && process.isAlive()) return;
        Files.createDirectories(workDir);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        log.info("Launching subserver '{}' on :{}", name, port);
        process = pb.start();

        stdoutPump = new Thread(this::pumpStdout, "ss-stdout-" + name);
        stdoutPump.setDaemon(true);
        stdoutPump.start();

        readinessProbe = new Thread(this::probeUntilReady, "ss-ready-" + name);
        readinessProbe.setDaemon(true);
        readinessProbe.start();
    }

    private void pumpStdout() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                backendLog.info("[{}] {}", name, line);
                pushLine(line);
            }
        } catch (IOException e) {
            log.debug("Stdout pump ended: {}", e.toString());
        }
    }

    private void probeUntilReady() {
        long deadline = System.currentTimeMillis() + 5 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (process == null || !process.isAlive()) return;
            String pingError = attemptStatusPing();
            if (pingError == null) {
                ready.set(true);
                log.info("Subserver '{}' is ready", name);
                return;
            }
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("{} did not become ready within timeout", name);
    }

    /**
     * Performs a full Minecraft Server List Ping (handshake + status request) and waits for the
     * status response. Returns {@code null} on success or a short error description on failure.
     * A successful response proves the backend has finished its startup sequence and is actually
     * processing protocol packets — not merely accepting raw TCP connections.
     */
    private String attemptStatusPing() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            s.setSoTimeout(2500);
            OutputStream out = s.getOutputStream();

            // Handshake packet: id=0, protocolVersion=774 (1.21.11), addr, port, nextState=1 (status)
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0x00);
            writeVarInt(hs, 774);
            writeMcString(hs, "127.0.0.1");
            hs.write((port >>> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 1);
            writeLengthPrefixed(out, hs.toByteArray());

            // Status request: id=0, empty body
            ByteArrayOutputStream sr = new ByteArrayOutputStream();
            writeVarInt(sr, 0x00);
            writeLengthPrefixed(out, sr.toByteArray());
            out.flush();

            InputStream in = s.getInputStream();
            int frameLen = readVarInt(in);
            if (frameLen <= 0 || frameLen > 1 << 20) return "bad status frame length " + frameLen;
            byte[] frame = readN(in, frameLen);
            int[] cursor = {0};
            int pid = readVarIntFromArray(frame, cursor);
            if (pid != 0x00) return "unexpected status packet id 0x" + Integer.toHexString(pid);
            int jsonLen = readVarIntFromArray(frame, cursor);
            if (jsonLen < 0 || cursor[0] + jsonLen > frame.length) return "bad json length " + jsonLen;
            // We don't need the body, just confirm we got a parseable response.
            return null;
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static void writeMcString(ByteArrayOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static void writeLengthPrefixed(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        writeVarInt(prefix, payload.length);
        out.write(prefix.toByteArray());
        out.write(payload);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("EOF reading VarInt");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 32) throw new IOException("VarInt too large");
        }
    }

    private static int readVarIntFromArray(byte[] arr, int[] cursor) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            if (cursor[0] >= arr.length) throw new IOException("EOF reading VarInt in frame");
            int b = arr[cursor[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 32) throw new IOException("VarInt too large in frame");
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r == -1) throw new IOException("EOF after " + read + " of " + n + " bytes");
            read += r;
        }
        return buf;
    }

    public synchronized void stop() {
        ready.set(false);
        if (process == null || !process.isAlive()) return;
        log.info("Stopping {}", name);
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {}
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("{} did not exit cleanly, killing", name);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
