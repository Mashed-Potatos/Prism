package dev.prism.proxy;

import dev.prism.subserver.Subserver;
import dev.prism.protocol.ConnectionState;
import dev.prism.protocol.ProtocolUtil;
import dev.prism.session.PlayerSession;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Drives a single in-flight seamless transfer.
 *
 * Flow (continues from ClientHandler.beginTransfer):
 *   1. A fresh BackendConnection is opened. It runs its own handshake/login and lands in CONFIG.
 *   2. onSetCompression / onLoginSuccess are silently consumed — the client is already past login.
 *   3. onPacket (CONFIG state from the new backend) is forwarded to the client so the new
 *      backend's registry data / finish-configuration / brand info reach the player.
 *   4. The new backend finishes config → client acks → both reach PLAY.
 *   5. onPlayLoginReceived: the new backend's Login (play) is forwarded to the client. This is
 *      the protocol-correct first PLAY packet after a CONFIG→PLAY transition. The client adopts
 *      the new entity id and the EID rewriter becomes a no-op.
 *   6. Subsequent packets are delegated to the player's ClientHandler for normal PLAY-state
 *      forwarding.
 */
public final class TransferListener implements BackendConnection.Listener {

    private static final Logger log = LoggerFactory.getLogger(TransferListener.class);

    private final ClientHandler client;
    private final PlayerSession session;
    private final Subserver target;
    private final CompletableFuture<Void> done;
    private boolean transferred;

    public TransferListener(ClientHandler client, PlayerSession session, Subserver target,
                            CompletableFuture<Void> done) {
        this.client = client;
        this.session = session;
        this.target = target;
        this.done = done;
    }

    @Override public void onSetCompression(BackendConnection b, int threshold) {
        // Backend-side compression is installed inside BackendConnection itself; the client
        // socket already has compression negotiated from the original backend.
    }

    @Override public void onLoginSuccess(BackendConnection b, ByteBuf payload) {
        payload.skipBytes(payload.readableBytes());
    }

    @Override public void onPacket(BackendConnection b, ByteBuf raw) {
        if (transferred) {
            client.onPacket(b, raw);
            return;
        }
        if (client.channelCtx().channel().isActive()) {
            client.channelCtx().writeAndFlush(raw);
        } else {
            raw.release();
        }
    }

    @Override public void onPlayLoginReceived(BackendConnection b, ByteBuf playLoginPayload) {
        LoginPlayPacket lp;
        try {
            lp = LoginPlayPacket.read(playLoginPayload.duplicate(), session.playIds().protocolVersion);
        } catch (Exception e) {
            log.warn("Transfer {} -> {} failed to parse Login(play): {}",
                    session.username(), target.name(), e.toString());
            playLoginPayload.release();
            if (!done.isDone()) done.completeExceptionally(e);
            return;
        }

        ByteBuf relogin = client.channelCtx().alloc().buffer(
                1 + playLoginPayload.readableBytes());
        ProtocolUtil.writeVarInt(relogin, session.playIds().clientboundLogin);
        relogin.writeBytes(playLoginPayload);
        client.channelCtx().writeAndFlush(relogin);

        session.currentDimensionType = lp.dimensionType;
        session.currentDimensionName = lp.dimensionName;
        session.clientEid = lp.entityId;
        session.backendEid = lp.entityId;
        session.setCurrentSubserver(target.name());

        client.setState(ConnectionState.PLAY);
        b.enterPlayState();

        playLoginPayload.release();
        transferred = true;
        done.complete(null);
    }

    @Override public void onClosed(BackendConnection b, String reason) {
        if (!transferred) {
            if (!done.isDone()) done.completeExceptionally(new RuntimeException(reason));
        } else {
            client.onClosed(b, reason);
        }
    }
}
