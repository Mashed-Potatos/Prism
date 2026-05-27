package dev.prism.panel;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session table for the admin panel. Each session is a random UUID
 * tied to a username, with a sliding 24-hour idle TTL refreshed on every access.
 */
public final class SessionStore {

    private static final long IDLE_TTL_MS = 24L * 60 * 60 * 1000;

    private final ConcurrentHashMap<UUID, Entry> sessions = new ConcurrentHashMap<>();

    public UUID create(String username) {
        UUID id = UUID.randomUUID();
        sessions.put(id, new Entry(username, System.currentTimeMillis()));
        return id;
    }

    public Optional<String> touch(UUID id) {
        if (id == null) return Optional.empty();
        Entry e = sessions.get(id);
        if (e == null) return Optional.empty();
        long now = System.currentTimeMillis();
        if (now - e.lastAccess > IDLE_TTL_MS) {
            sessions.remove(id);
            return Optional.empty();
        }
        e.lastAccess = now;
        return Optional.of(e.username);
    }

    public void invalidate(UUID id) {
        if (id != null) sessions.remove(id);
    }

    private static final class Entry {
        final String username;
        volatile long lastAccess;
        Entry(String username, long lastAccess) { this.username = username; this.lastAccess = lastAccess; }
    }
}
