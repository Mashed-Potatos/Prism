package dev.prism.proxy;

import dev.prism.PrismContext;
import dev.prism.crypto.EncryptionUtil;
import dev.prism.crypto.GameProfile;
import dev.prism.protocol.*;
import dev.prism.session.ChatRouter;
import dev.prism.session.EntityIdRewriter;
import dev.prism.session.PlayerSession;
import dev.prism.subserver.Subserver;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Per-client Netty handler. Drives the full login lifecycle including the
 * online-mode encryption handshake and Mojang sessionserver check.
 *
 * Online-mode flow:
 *   1. Receive Login Start (username + hint UUID, both untrusted).
 *   2. Send Encryption Request with the proxy's persistent RSA public key + a fresh
 *      verify token.
 *   3. Receive Encryption Response, RSA-decrypt the shared secret and the round-trip
 *      verify token, install AES/CFB8 at the head of the pipeline.
 *   4. Compute the Mojang server hash and hit sessionserver/hasJoined on a worker
 *      thread; resume on the channel's event loop.
 *   5. Open the backend with the verified GameProfile; the BackendConnection delivers
 *      the real UUID + properties through whichever forwarding mode is configured.
 *
 * Offline-mode bypasses steps 2-4 and synthesizes a GameProfile from the username.
 */
public final class ClientHandler extends SimpleChannelInboundHandler<ByteBuf> implements BackendConnection.Listener {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum LoginPhase { AWAIT_LOGIN_START, AWAIT_ENCRYPTION_RESPONSE, AUTHENTICATING, AWAIT_LOGIN_ACK }

    private final PrismContext ctx;
    private ChannelHandlerContext channelCtx;

    private ConnectionState state = ConnectionState.HANDSHAKE;
    private LoginPhase loginPhase = LoginPhase.AWAIT_LOGIN_START;

    private int protocolVersion;
    private PlayPacketIds playIds;
    private String pendingHost;
    private int pendingPort;
    private String username;
    private UUID hintUuid;
    private String clientAddress;

    // Online-mode encryption handshake state
    private byte[] verifyToken;
    private final String serverId = ""; // modern convention: empty string

    private GameProfile gameProfile;
    private BackendConnection backend;
    private PlayerSession session;

    public ClientHandler(PrismContext ctx) { this.ctx = ctx; }

    @Override public void channelActive(ChannelHandlerContext c) {
        this.channelCtx = c;
        if (c.channel().remoteAddress() instanceof InetSocketAddress isa) {
            this.clientAddress = isa.getAddress().getHostAddress();
        } else {
            this.clientAddress = "127.0.0.1";
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext c, ByteBuf msg) {
        int id = ProtocolUtil.readVarInt(msg);
        switch (state) {
            case HANDSHAKE -> handleHandshake(c, id, msg);
            case STATUS    -> handleStatus(c, id, msg);
            case LOGIN     -> handleLogin(c, id, msg);
            case CONFIGURATION, PLAY -> forwardToBackendRaw(id, msg);
        }
    }

    // ---------------- handshake / status ----------------

    private void handleHandshake(ChannelHandlerContext c, int id, ByteBuf msg) {
        if (id != PacketIds.SB_HANDSHAKE) { c.close(); return; }
        protocolVersion = ProtocolUtil.readVarInt(msg);
        pendingHost = ProtocolUtil.readString(msg, 255);
        pendingPort = msg.readUnsignedShort();
        int nextState = ProtocolUtil.readVarInt(msg);
        state = nextState == 1 ? ConnectionState.STATUS : ConnectionState.LOGIN;
        playIds = PlayPacketIds.forProtocol(protocolVersion);
        if (playIds.protocolVersion != protocolVersion) {
            log.warn("Client {} advertised protocol {}, falling back to {} table",
                    c.channel().remoteAddress(), protocolVersion, playIds);
        }
    }

    private void handleStatus(ChannelHandlerContext c, int id, ByteBuf msg) {
        if (id == PacketIds.SB_STATUS_REQUEST) {
            String baseMotd = ctx.config.motd;
            if (ctx.subservers.runningCount() >= dev.prism.subserver.SubserverManager.MAX_SUBSERVERS) {
                baseMotd = baseMotd + " §c[TRIAL]";
            }
            String motd = baseMotd.replace("\"", "\\\"");
            String json = "{\"version\":{\"name\":\"Prism " + playIds.label + "\",\"protocol\":" + protocolVersion + "}," +
                    "\"players\":{\"max\":" + ctx.config.maxPlayers + ",\"online\":" + ctx.sessions.count() + "}," +
                    "\"description\":{\"text\":\"" + motd + "\"}}";
            ByteBuf out = c.alloc().buffer();
            ProtocolUtil.writeVarInt(out, PacketIds.CB_STATUS_RESPONSE);
            ProtocolUtil.writeString(out, json);
            c.writeAndFlush(out);
        } else if (id == PacketIds.SB_PING_REQUEST) {
            long payload = msg.readLong();
            ByteBuf out = c.alloc().buffer();
            ProtocolUtil.writeVarInt(out, PacketIds.CB_PONG_RESPONSE);
            out.writeLong(payload);
            c.writeAndFlush(out).addListener(f -> c.close());
        }
    }

    // ---------------- LOGIN state ----------------

    private void handleLogin(ChannelHandlerContext c, int id, ByteBuf msg) {
        switch (loginPhase) {
            case AWAIT_LOGIN_START -> {
                if (id != PacketIds.SB_LOGIN_START) return;
                username = ProtocolUtil.readString(msg, 16);
                hintUuid = msg.readableBytes() >= 16 ? ProtocolUtil.readUuid(msg) : null;

                if (ctx.config.onlineMode) {
                    sendEncryptionRequest();
                    loginPhase = LoginPhase.AWAIT_ENCRYPTION_RESPONSE;
                } else {
                    // Offline-mode synth profile.
                    UUID offline = ProtocolUtil.offlineUuid(username);
                    gameProfile = new GameProfile(offline, username, Collections.emptyList());
                    proceedToBackend();
                }
            }
            case AWAIT_ENCRYPTION_RESPONSE -> {
                if (id != PacketIds.SB_ENCRYPTION_RESPONSE) return;
                handleEncryptionResponse(msg);
            }
            case AWAIT_LOGIN_ACK -> {
                if (id == PacketIds.SB_LOGIN_ACK) {
                    state = ConnectionState.CONFIGURATION;
                    backend.enterConfigState();
                }
            }
            case AUTHENTICATING -> {
                // Drop unexpected packets while we're waiting for hasJoined.
            }
        }
    }

    private void sendEncryptionRequest() {
        verifyToken = new byte[4];
        RANDOM.nextBytes(verifyToken);

        byte[] pubKey = ctx.keys.publicKeyEncoded();
        ByteBuf out = channelCtx.alloc().buffer();
        ProtocolUtil.writeVarInt(out, PacketIds.CB_ENCRYPTION_REQ);
        ProtocolUtil.writeString(out, serverId);
        ProtocolUtil.writeVarInt(out, pubKey.length);
        out.writeBytes(pubKey);
        ProtocolUtil.writeVarInt(out, verifyToken.length);
        out.writeBytes(verifyToken);
        out.writeBoolean(true); // shouldAuthenticate (1.20.5+ field)
        channelCtx.writeAndFlush(out);
    }

    private void handleEncryptionResponse(ByteBuf msg) {
        int sharedLen = ProtocolUtil.readVarInt(msg);
        byte[] encShared = new byte[sharedLen]; msg.readBytes(encShared);
        int tokenLen = ProtocolUtil.readVarInt(msg);
        byte[] encToken = new byte[tokenLen]; msg.readBytes(encToken);

        byte[] sharedSecret;
        byte[] echoedToken;
        try {
            sharedSecret = EncryptionUtil.rsaDecrypt(ctx.keys.privateKey(), encShared);
            echoedToken = EncryptionUtil.rsaDecrypt(ctx.keys.privateKey(), encToken);
        } catch (Exception e) {
            disconnectInLogin("Could not decrypt the encryption response: " + e.getMessage());
            return;
        }
        if (!Arrays.equals(verifyToken, echoedToken)) {
            disconnectInLogin("Verify token mismatch — client encryption failed.");
            return;
        }
        if (sharedSecret.length != 16) {
            disconnectInLogin("Unexpected shared-secret length: " + sharedSecret.length);
            return;
        }

        // Install AES/CFB8 at the head of the pipeline. Every byte after this point is encrypted.
        try {
            Cipher dec = EncryptionUtil.aesCfb8(sharedSecret, Cipher.DECRYPT_MODE);
            Cipher enc = EncryptionUtil.aesCfb8(sharedSecret, Cipher.ENCRYPT_MODE);
            channelCtx.pipeline().addFirst("encrypt", CipherCodec.outbound(enc));
            channelCtx.pipeline().addFirst("decrypt", CipherCodec.inbound(dec));
        } catch (Exception e) {
            disconnectInLogin("Cipher setup failed: " + e.getMessage());
            return;
        }

        String hash = EncryptionUtil.serverHash(serverId, sharedSecret, ctx.keys.publicKeyEncoded());
        loginPhase = LoginPhase.AUTHENTICATING;

        ctx.mojang.hasJoined(username, hash).whenComplete((profile, err) ->
            channelCtx.executor().execute(() -> {
                if (err != null) {
                    log.warn("Auth failed for {}: {}", username, err.toString());
                    disconnectInLogin("Authentication failed: " + err.getMessage());
                    return;
                }
                gameProfile = profile;
                proceedToBackend();
            })
        );
    }

    private void proceedToBackend() {
        Subserver target = ctx.subservers.defaultSubserver().orElse(null);
        if (target == null || !target.isReady()) {
            disconnectInLogin("No backend available yet, try again shortly.");
            return;
        }
        log.info("{} joined -> {}", gameProfile.name, target.name());
        backend = new BackendConnection(ctx, target, gameProfile, clientAddress, protocolVersion, playIds, this);
        backend.open().exceptionally(t -> {
            disconnectInLogin("Backend unreachable: " + t.getMessage());
            return null;
        });
        loginPhase = LoginPhase.AWAIT_LOGIN_ACK;
    }

    private void disconnectInLogin(String reason) {
        String json = "{\"text\":\"" + reason.replace("\"", "\\\"") + "\"}";
        ByteBuf out = channelCtx.alloc().buffer();
        ProtocolUtil.writeVarInt(out, PacketIds.CB_LOGIN_DISCONNECT);
        ProtocolUtil.writeString(out, json);
        channelCtx.writeAndFlush(out).addListener(f -> channelCtx.close());
    }

    // ---------------- CONFIG / PLAY forwarding ----------------

    private void forwardToBackendRaw(int id, ByteBuf msg) {
        if (backend == null) return;

        // The client's Configuration Acknowledged during a transfer must not reach any backend
        // — it is the trigger that lets us safely close the OLD backend and open the NEW one.
        if (state == ConnectionState.CONFIGURATION && id == playIds.serverboundAckConfig) {
            onClientConfigAck();
            return;
        }

        if (state == ConnectionState.PLAY && id == playIds.serverboundChatMessage && session != null) {
            try {
                String text = ChatRouter.extractText(msg.duplicate());
                ctx.chatRouter.onChat(session, text);
            } catch (Exception ignored) {}
        }

        // /server <name> — proxy-level transfer. Intercept BEFORE the backend sees it.
        if (state == ConnectionState.PLAY && id == playIds.serverboundChatCommand && session != null) {
            try {
                String command = ProtocolUtil.readString(msg.duplicate(), 256);
                if (handleProxyChatCommand(command)) return;
            } catch (Exception ignored) {}
        }

        ByteBuf raw = channelCtx.alloc().buffer(ProtocolUtil.varIntSize(id) + msg.readableBytes());
        ProtocolUtil.writeVarInt(raw, id);
        raw.writeBytes(msg);

        if (state == ConnectionState.CONFIGURATION && id == PacketIds.SB_CFG_ACK_FINISH) {
            state = ConnectionState.PLAY;
            backend.enterPlayState();
            if (session == null) session = ctx.sessions.register(gameProfile.name, gameProfile.uuid, this, playIds);
        }
        backend.writeRaw(raw);
    }

    // ---------------- BackendConnection.Listener ----------------

    @Override public void onLoginSuccess(BackendConnection b, ByteBuf payload) {
        if (b != backend) return;
        ByteBuf out = channelCtx.alloc().buffer();
        ProtocolUtil.writeVarInt(out, PacketIds.CB_LOGIN_SUCCESS);
        out.writeBytes(payload);
        channelCtx.writeAndFlush(out);
    }

    @Override public void onSetCompression(BackendConnection b, int threshold) {
        if (b != backend) return;
        if (threshold < 0) return;
        ByteBuf out = channelCtx.alloc().buffer();
        ProtocolUtil.writeVarInt(out, PacketIds.CB_SET_COMPRESSION);
        ProtocolUtil.writeVarInt(out, threshold);
        channelCtx.writeAndFlush(out);
        channelCtx.pipeline().addAfter("frame", "compress", new CompressionCodec(threshold));
    }

    @Override public void onPlayLoginReceived(BackendConnection b, ByteBuf playLoginPayload) {
        if (b != backend) { playLoginPayload.release(); return; }
        LoginPlayPacket lp = LoginPlayPacket.read(playLoginPayload.duplicate(), protocolVersion);

        if (session == null) {
            session = ctx.sessions.register(gameProfile.name, gameProfile.uuid, this, playIds);
        }
        session.currentDimensionType = lp.dimensionType;
        session.currentDimensionName = lp.dimensionName;
        session.clientEid = lp.entityId;
        session.backendEid = lp.entityId;

        ByteBuf out = channelCtx.alloc().buffer();
        ProtocolUtil.writeVarInt(out, playIds.clientboundLogin);
        out.writeBytes(playLoginPayload);
        channelCtx.writeAndFlush(out);
        playLoginPayload.release();
    }

    @Override public void onPacket(BackendConnection b, ByteBuf raw) {
        // Drop packets from a stale backend (e.g., late drains from the old backend
        // during a transfer). Writing them to the client now would deliver PLAY-state
        // packets while the client has already transitioned to CONFIG.
        if (b != backend) { raw.release(); return; }
        if (!channelCtx.channel().isActive()) { raw.release(); return; }
        // Ack-wait window: we sent START_CONFIG to the client and the new backend hasn't
        // been opened yet, so `backend` is still the OLD backend. Its packet stream is
        // PLAY-state, but the client is now in CONFIG. Forwarding any of these would be a
        // protocol error and the client would disconnect.
        if (pendingTransferTarget != null) {
            raw.release();
            return;
        }
        if (session != null && session.clientEid != session.backendEid) {
            raw = EntityIdRewriter.rewriteClientbound(channelCtx.alloc(), raw, playIds,
                    session.backendEid, session.clientEid);
        }
        channelCtx.writeAndFlush(raw);
    }

    @Override public void onClosed(BackendConnection b, String reason) {
        // Transfer guard: while a seamless transfer is in flight, an old-backend close must NOT
        // tear down the client channel — TransferListener is still driving the new backend
        // handshake. The in-flight flag is the source of truth.
        if (session != null && session.isTransferring()) return;
        if (b != backend) return;
        if (channelCtx != null && channelCtx.channel().isActive()) channelCtx.close();
    }

    @Override public void channelInactive(ChannelHandlerContext c) {
        if (session != null && session.isTransferring()) session.setTransferring(false);
        if (pendingTransferDone != null && !pendingTransferDone.isDone()) {
            pendingTransferDone.completeExceptionally(new RuntimeException("client channel inactive"));
        }
        clearPendingTransfer();
        if (backend != null) backend.close();
        if (session != null) ctx.sessions.remove(session);
    }

    @Override public void exceptionCaught(ChannelHandlerContext c, Throwable cause) {
        log.warn("Client {} error: {}", username == null ? c.channel().remoteAddress() : username, cause.toString());
        c.close();
    }

    // ---------------- /server proxy command ----------------

    /**
     * Returns true if the chat command should be swallowed by the proxy (not forwarded).
     * Currently only "/server <name>" is intercepted; everything else passes through.
     */
    private boolean handleProxyChatCommand(String command) {
        if (command == null) return false;
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0 || !"server".equalsIgnoreCase(parts[0])) return false;

        if (parts.length < 2) {
            ctx.chatRouter.sendSystemChat(session, "§eUsage:§r /server <name>. Available: " + listAvailableSubservers());
            return true;
        }
        String targetName = parts[1];
        Subserver target = ctx.subservers.get(targetName).orElse(null);
        if (target == null) {
            ctx.chatRouter.sendSystemChat(session,
                    "§cUnknown subserver '" + targetName + "'.§r Available: " + listAvailableSubservers());
            return true;
        }
        if (targetName.equalsIgnoreCase(session.currentSubserver())) {
            ctx.chatRouter.sendSystemChat(session, "§eAlready on " + targetName + ".");
            return true;
        }
        if (!target.isReady()) {
            ctx.chatRouter.sendSystemChat(session, "§c" + targetName + " is not ready yet — try again shortly.");
            return true;
        }
        ctx.chatRouter.sendSystemChat(session, "§7Transferring to " + targetName + "…");
        ctx.sessions.transfer(session, targetName).whenComplete((v, t) -> {
            if (t != null && channelCtx != null && channelCtx.channel().isActive()) {
                ctx.chatRouter.sendSystemChat(session, "§cTransfer failed: " + t.getMessage());
            }
        });
        return true;
    }

    private String listAvailableSubservers() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Subserver s : ctx.subservers.all()) {
            if (!first) sb.append(", ");
            sb.append(s.name());
            first = false;
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    // ---------------- transfer ----------------

    private static final int TRANSFER_MAX_ATTEMPTS = 3;
    private static final long TRANSFER_RETRY_DELAY_MS = 500L;
    /** Hard cap for how long we'll wait on the client to ACK START_CONFIG before giving up. */
    private static final long TRANSFER_ACK_TIMEOUT_MS = 10_000L;

    /** Set when beginTransfer kicks off and cleared once the client ACKs START_CONFIG. */
    private Subserver pendingTransferTarget;
    private CompletableFuture<Void> pendingTransferDone;
    private java.util.concurrent.ScheduledFuture<?> pendingAckTimeout;

    public CompletableFuture<Void> beginTransfer(Subserver target) {
        if (session == null) return CompletableFuture.failedFuture(new IllegalStateException("not in PLAY yet"));
        if (state != ConnectionState.PLAY) {
            return CompletableFuture.failedFuture(new IllegalStateException("client not in PLAY (state=" + state + ")"));
        }
        if (pendingTransferTarget != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("transfer already in progress"));
        }

        // The old backend stays alive until the client ACKs START_CONFIG; closing it earlier
        // would race the client's transition and produce a disconnect.
        session.setTransferring(true);

        CompletableFuture<Void> done = new CompletableFuture<>();
        pendingTransferTarget = target;
        pendingTransferDone = done;

        ByteBuf startCfg = channelCtx.alloc().buffer();
        ProtocolUtil.writeVarInt(startCfg, playIds.clientboundStartConfig);
        channelCtx.writeAndFlush(startCfg);
        state = ConnectionState.CONFIGURATION;

        pendingAckTimeout = channelCtx.executor().schedule(() -> {
            if (pendingTransferTarget != null && !done.isDone()) {
                log.warn("Transfer {} -> {} timed out waiting for client ACK", username, target.name());
                clearPendingTransfer();
                done.completeExceptionally(new RuntimeException("client did not ACK START_CONFIG"));
            }
        }, TRANSFER_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        done.whenComplete((v, err) -> {
            session.setTransferring(false);
            if (err == null) {
                log.info("{} transferred to {}", username, target.name());
            } else {
                log.warn("Transfer {} -> {} failed: {}", username, target.name(), err.toString());
                if (channelCtx != null && channelCtx.channel().isActive()) channelCtx.close();
            }
        });
        return done;
    }

    /**
     * Called when the client sends SB_CFG_ACK in response to our START_CONFIG. The client has
     * transitioned to CONFIG and is ready to receive registry data and finish-configuration
     * from the new backend, so it is now safe to close the old backend and open the new one.
     */
    private void onClientConfigAck() {
        Subserver target = pendingTransferTarget;
        CompletableFuture<Void> done = pendingTransferDone;
        if (target == null || done == null) return;
        if (pendingAckTimeout != null) { pendingAckTimeout.cancel(false); pendingAckTimeout = null; }

        if (backend != null) backend.close();
        pendingTransferTarget = null;
        pendingTransferDone = null;

        attemptBackendOpen(target, 1, done);
    }

    private void clearPendingTransfer() {
        pendingTransferTarget = null;
        pendingTransferDone = null;
        if (pendingAckTimeout != null) { pendingAckTimeout.cancel(false); pendingAckTimeout = null; }
    }

    private void attemptBackendOpen(Subserver target, int attempt, CompletableFuture<Void> done) {
        CompletableFuture<Void> attemptDone = new CompletableFuture<>();
        TransferListener tl = new TransferListener(this, session, target, attemptDone);
        BackendConnection newBackend = new BackendConnection(ctx, target, gameProfile, clientAddress,
                protocolVersion, playIds, tl);
        backend = newBackend;
        newBackend.open().exceptionally(t -> {
            attemptDone.completeExceptionally(t);
            return null;
        });

        attemptDone.whenComplete((v, err) -> {
            if (done.isDone()) return;
            if (err == null) { done.complete(null); return; }
            if (attempt >= TRANSFER_MAX_ATTEMPTS) {
                done.completeExceptionally(err);
                return;
            }
            channelCtx.executor().schedule(
                    () -> { if (!done.isDone()) attemptBackendOpen(target, attempt + 1, done); },
                    TRANSFER_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        });
    }

    // helpers used by TransferListener
    public ChannelHandlerContext channelCtx() { return channelCtx; }
    public String username() { return username; }
    public PlayPacketIds playIds() { return playIds; }
    public BackendConnection backend() { return backend; }
    public ConnectionState state() { return state; }
    public void setState(ConnectionState s) { this.state = s; }
}
