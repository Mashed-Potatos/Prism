package dev.prism.session;

import dev.prism.protocol.PlayPacketIds;
import dev.prism.proxy.ClientHandler;

import java.util.UUID;

/**
 * Per-player state carried for the lifetime of a single client connection.
 *
 * <p>Tracks the current backend, the player's last-known world dimension (so we
 * can synthesize the dimension-flush Respawn during a seamless transfer), and the
 * pair of entity ids the client and the current backend disagree on after a
 * transfer.
 *
 * <p>All mutable fields are {@code volatile} because reads happen on whichever
 * event loop is processing the next packet, while writes may originate from the
 * console thread, the panel HTTP thread, or any backend's event loop.
 */
public final class PlayerSession {

    private final String username;
    private final UUID uuid;
    private final ClientHandler client;
    private final PlayPacketIds playIds;

    private volatile String currentSubserver;

    // Snapshot of the player's view of the world, captured from the first Login (play)
    // and updated on every Respawn we synthesize. Used to drive the seamless-transfer
    // dimension-flush trick.
    public volatile int  currentDimensionType;
    public volatile String currentDimensionName = "minecraft:overworld";

    // The EID the client itself uses (set once, from the very first Login (play)) and the
    // EID the current backend assigns to the player (refreshed each transfer). Mismatch
    // is corrected by EntityIdRewriter on a handful of clientbound packets.
    public volatile int clientEid;
    public volatile int backendEid;

    /** True while a seamless transfer is in flight. Set in ClientHandler.beginTransfer
     *  BEFORE closing the old backend, cleared when the transfer completes (success) or
     *  when retries are fully exhausted (failure). While true, an old-backend channelInactive
     *  must not tear down the session or the client channel — the new backend handshake is
     *  still being driven by TransferListener. */
    private volatile boolean transferring;
    public boolean isTransferring() { return transferring; }
    public void setTransferring(boolean t) { this.transferring = t; }

    public PlayerSession(String username, UUID uuid, ClientHandler client, PlayPacketIds playIds) {
        this.username = username;
        this.uuid = uuid;
        this.client = client;
        this.playIds = playIds;
        this.currentSubserver = client.backend() == null ? null : client.backend().target().name();
    }

    public String username() { return username; }
    public UUID uuid() { return uuid; }
    public ClientHandler client() { return client; }
    public PlayPacketIds playIds() { return playIds; }
    public String currentSubserver() { return currentSubserver; }
    public void setCurrentSubserver(String g) { this.currentSubserver = g; }
}
