package dev.prism.console;

import dev.prism.PrismContext;
import dev.prism.session.PlayerSession;
import dev.prism.subserver.PluginSync;
import dev.prism.subserver.Subserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Single stdin reader for admin commands.
 *
 * Console context:
 *   The console has an active subserver context. Unrecognized commands are piped to
 *   that context's backend stdin. The context defaults to the configured
 *   default-subserver if set, otherwise the first registered subserver, resolved
 *   lazily on first use. {@code /console <name>} switches the context.
 *
 * Forms:
 *   /console <subserver>              switch the active context
 *   <subserver>: <command>            one-shot pipe into another backend's stdin
 *   stopall                           graceful shutdown of all backends + Prism
 *   status                            print subserver + session counts
 *   broadcast <message>               System Chat to every connected player
 *   transfer <player> <subserver>     seamless backend transfer
 *   sync <from> <to> <all|Name>       copy plugin jar(s) + config(s) between subservers
 *   help                              list commands
 *   <anything else>                   piped to the active context (e.g. "op Potato")
 *
 * Every dispatched command is echoed as {@code [<subserver>] > <command>} so the
 * destination is always visible.
 */
public final class PrismConsole {

    private static final Logger log = LoggerFactory.getLogger(PrismConsole.class);

    private final PrismContext ctx;
    private final Runnable stopAll;
    private Thread thread;

    /** Null until first resolved; resolved lazily so we can pick up subservers that
     *  appear after startup. */
    private String currentContext;

    public PrismConsole(PrismContext ctx, Runnable stopAll) {
        this.ctx = ctx;
        this.stopAll = stopAll;
    }

    public void start() {
        thread = new Thread(this::run, "prism-console");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        log.info("Console ready. Type 'help' for commands. The active context is resolved on first use " +
                "(use /console <name> to set it explicitly).");
        try {
            while (true) {
                printPrompt();
                String line = r.readLine();
                if (line == null) break;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    handle(trimmed);
                } catch (Exception e) {
                    log.warn("Console command failed: {}", e.toString());
                }
            }
        } catch (Exception e) {
            log.debug("Console reader ended: {}", e.toString());
        }
    }

    private void printPrompt() {
        String ctxName = resolveContext();
        System.out.print((ctxName == null ? "(no subserver)" : ctxName) + "> ");
        System.out.flush();
    }

    /** Resolves the active context lazily: explicit {@code /console} wins, else
     *  {@code default-subserver}, else the first registered subserver. May return null
     *  if no subservers are registered yet. */
    private String resolveContext() {
        if (currentContext != null && ctx.subservers.get(currentContext).isPresent()) {
            return currentContext;
        }
        if (ctx.config.defaultSubserver != null
                && ctx.subservers.get(ctx.config.defaultSubserver).isPresent()) {
            currentContext = ctx.config.defaultSubserver;
            return currentContext;
        }
        return ctx.subservers.firstName().orElse(null);
    }

    private void handle(String line) {
        // /console <name> — switch active context
        if (line.equalsIgnoreCase("/console") || line.toLowerCase().startsWith("/console ")) {
            String name = line.length() > "/console".length()
                    ? line.substring("/console".length()).trim() : "";
            if (name.isEmpty()) {
                log.info("Usage: /console <subserver>. Current context: {}", currentContext);
                return;
            }
            if (ctx.subservers.get(name).isEmpty()) {
                log.info("/console: no such subserver '{}' (context still {})", name, currentContext);
                return;
            }
            currentContext = name;
            log.info("Console context switched to {}", currentContext);
            return;
        }

        // <subserver>: <command> — one-shot pipe to another backend
        int colon = indexOfOutsideQuotes(line, ':');
        if (colon > 0 && !startsWithBuiltin(line, colon)) {
            String subserver = line.substring(0, colon).trim();
            String cmd = line.substring(colon + 1).trim();
            log.info("[{}] > {}", subserver, cmd);
            pipeToSubserver(subserver, cmd);
            return;
        }

        String[] parts = line.split("\\s+", 2);
        String head = parts[0].toLowerCase();
        String tail = parts.length > 1 ? parts[1] : "";

        switch (head) {
            case "help" -> printHelp();
            case "stopall" -> { log.info("Stopall requested"); stopAll.run(); }
            case "status" -> printStatus();
            case "broadcast" -> {
                if (tail.isEmpty()) { log.info("Usage: broadcast <message>"); return; }
                ctx.chatRouter.broadcastEveryone("[Prism] " + tail);
            }
            case "transfer" -> {
                String[] tp = tail.split("\\s+", 2);
                if (tp.length < 2) { log.info("Usage: transfer <player> <subserver>"); return; }
                PlayerSession s = ctx.sessions.find(tp[0]).orElse(null);
                if (s == null) { log.info("No such player: {}", tp[0]); return; }
                ctx.sessions.transfer(s, tp[1]).whenComplete((v, t) -> {
                    if (t != null) log.warn("Transfer failed: {}", t.toString());
                    else log.info("{} transferred to {}", s.username(), tp[1]);
                });
            }
            case "sync" -> handleSync(tail);
            case "stop" -> handleStop(tail);
            case "start" -> handleStart(tail);
            case "restart" -> handleRestart(tail);
            case "startall" -> handleStartAll();
            case "restartall" -> handleRestartAll();
            default -> {
                // Unrecognized commands are piped to the active context's stdin. This
                // is how a bare "op Potato" reaches the backend without any prefix.
                String ctxName = resolveContext();
                if (ctxName == null) {
                    log.info("No subservers registered — nothing to pipe '{}' to. " +
                            "Drop a server.jar into a subfolder and restart, or use /console <name>.", line);
                    return;
                }
                log.info("[{}] > {}", ctxName, line);
                pipeToSubserver(ctxName, line);
            }
        }
    }

    private boolean startsWithBuiltin(String line, int colon) {
        String prefix = line.substring(0, colon).trim().toLowerCase();
        return prefix.equals("help") || prefix.equals("stopall") || prefix.equals("status")
                || prefix.equals("broadcast") || prefix.equals("transfer") || prefix.equals("sync")
                || prefix.equals("stop") || prefix.equals("start") || prefix.equals("restart")
                || prefix.equals("startall") || prefix.equals("restartall");
    }

    private static int indexOfOutsideQuotes(String s, char ch) {
        boolean inQ = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQ = !inQ;
            else if (!inQ && c == ch) return i;
        }
        return -1;
    }

    private void pipeToSubserver(String subserverName, String command) {
        Subserver s = ctx.subservers.get(subserverName).orElse(null);
        if (s == null) { log.info("No such subserver: {}", subserverName); return; }
        if (!s.process().isAlive()) { log.info("{} is not running", subserverName); return; }
        s.process().sendCommand(command);
    }

    private void printStatus() {
        log.info("--- Prism status --- (active context: {})", resolveContext());
        Map<String, Long> playersBySubserver = new java.util.HashMap<>();
        for (PlayerSession p : ctx.sessions.all()) {
            playersBySubserver.merge(p.currentSubserver(), 1L, Long::sum);
        }
        for (Subserver s : ctx.subservers.all()) {
            long n = playersBySubserver.getOrDefault(s.name(), 0L);
            log.info("  {} :{} ready={} players={}", s.name(), s.port(), s.isReady(), n);
        }
        log.info("  total sessions: {}", ctx.sessions.count());
    }

    private void printHelp() {
        log.info("Commands (active context: {}):", resolveContext());
        log.info("  /console <subserver>               switch active context");
        log.info("  <subserver>: <command>             one-shot pipe into another backend's stdin");
        log.info("  <anything else>                    piped to active context (e.g. 'op Potato')");
        log.info("  status                             list subservers + player counts");
        log.info("  broadcast <message>                System Chat to all players");
        log.info("  transfer <player> <subserver>      seamless backend transfer");
        log.info("  sync <from> <to> <all|Name>        copy plugin jar(s) + config(s) between subservers");
        log.info("  stop <subserver>                   gracefully stop one subserver");
        log.info("  start <subserver>                  start one subserver");
        log.info("  restart <subserver>                stop then start one subserver");
        log.info("  startall                           start every registered subserver not running");
        log.info("  restartall                         restart every registered subserver one by one");
        log.info("  stopall                            graceful shutdown of all backends + Prism");
    }

    private void handleStop(String tail) {
        String name = tail.trim();
        if (name.isEmpty()) { log.info("Usage: stop <subserver>"); return; }
        if (ctx.subservers.get(name).isEmpty()) { log.info("No such subserver: {}", name); return; }
        log.info("Stopping {}...", name);
        if (ctx.subservers.stop(name)) log.info("Stop requested for {}", name);
        else log.info("Stop {} failed", name);
    }

    private void handleStart(String tail) {
        String name = tail.trim();
        if (name.isEmpty()) { log.info("Usage: start <subserver>"); return; }
        Subserver s = ctx.subservers.get(name).orElse(null);
        if (s == null) { log.info("No such subserver: {}", name); return; }
        if (s.process().isAlive()) { log.info("{} is already running", name); return; }
        log.info("Starting {}...", name);
        if (ctx.subservers.start(name)) log.info("Start requested for {}", name);
        else log.info("Start {} failed", name);
    }

    private void handleRestart(String tail) {
        String name = tail.trim();
        if (name.isEmpty()) { log.info("Usage: restart <subserver>"); return; }
        if (ctx.subservers.get(name).isEmpty()) { log.info("No such subserver: {}", name); return; }
        log.info("Restarting {}...", name);
        if (ctx.subservers.restart(name)) log.info("Restart requested for {}", name);
        else log.info("Restart {} failed", name);
    }

    private void handleStartAll() {
        int started = 0, skipped = 0;
        for (Subserver s : ctx.subservers.all()) {
            if (s.process().isAlive()) { skipped++; continue; }
            log.info("Starting {}...", s.name());
            if (ctx.subservers.start(s.name())) started++;
            else log.info("Start {} failed", s.name());
        }
        log.info("Startall: {} started, {} already running", started, skipped);
    }

    private void handleRestartAll() {
        int n = 0;
        for (Subserver s : ctx.subservers.all()) {
            log.info("Restarting {}...", s.name());
            if (ctx.subservers.restart(s.name())) n++;
            else log.info("Restart {} failed", s.name());
        }
        log.info("Restartall: {} subserver(s) processed", n);
    }

    private void handleSync(String tail) {
        String[] parts = tail.split("\\s+");
        if (parts.length < 3 || parts[0].isEmpty()) {
            log.info("Usage: sync <from-subserver> <to-subserver> <all|PluginName>");
            return;
        }
        String fromName = parts[0];
        String toName = parts[1];
        String filter = parts[2];
        if (fromName.equalsIgnoreCase(toName)) {
            log.info("Sync: source and target are the same ({})", fromName);
            return;
        }
        Subserver from = ctx.subservers.get(fromName).orElse(null);
        if (from == null) { log.info("Sync: no such subserver: {}", fromName); return; }
        Subserver to = ctx.subservers.get(toName).orElse(null);
        if (to == null) { log.info("Sync: no such subserver: {}", toName); return; }
        PluginSync.sync(fromName, toName,
                from.folder().resolve("plugins"),
                to.folder().resolve("plugins"),
                filter);
    }
}
