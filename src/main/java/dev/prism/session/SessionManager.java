package dev.prism.session;

import dev.prism.PrismContext;
import dev.prism.proxy.ClientHandler;
import dev.prism.subserver.Subserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live PlayerSessions and exposes the transfer entrypoint. The actual
 * transfer state machine lives on ClientHandler so it can drive the client's
 * Netty pipeline directly.
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<UUID, PlayerSession> byUuid = new ConcurrentHashMap<>();
    private PrismContext ctx;

    public void attach(PrismContext ctx) { this.ctx = ctx; }

    public int count() { return byUuid.size(); }

    public PlayerSession register(String username, UUID uuid, ClientHandler handler,
                                  dev.prism.protocol.PlayPacketIds playIds) {
        PlayerSession s = new PlayerSession(username, uuid, handler, playIds);
        byUuid.put(uuid, s);
        return s;
    }

    public void remove(PlayerSession s) {
        byUuid.remove(s.uuid());
        log.info("{} disconnected", s.username());
    }

    public Optional<PlayerSession> find(String username) {
        return byUuid.values().stream().filter(s -> s.username().equalsIgnoreCase(username)).findFirst();
    }

    public Collection<PlayerSession> all() { return byUuid.values(); }

    public CompletableFuture<Void> transfer(PlayerSession session, String targetSubserverName) {
        Subserver target = ctx.subservers.get(targetSubserverName).orElse(null);
        if (target == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no such subserver: " + targetSubserverName));
        if (!target.isReady()) return CompletableFuture.failedFuture(new IllegalStateException("subserver not ready: " + targetSubserverName));
        if (targetSubserverName.equals(session.currentSubserver())) return CompletableFuture.completedFuture(null);
        return session.client().beginTransfer(target);
    }
}
