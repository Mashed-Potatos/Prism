package dev.prism.proxy;

import dev.prism.PrismContext;
import dev.prism.config.ForwardingConfig;
import dev.prism.crypto.GameProfile;
import dev.prism.protocol.*;
import dev.prism.subserver.Subserver;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * One TCP connection to a backend subserver. Drives login on behalf of a real
 * player (backends run in offline-mode; Prism injects the verified identity).
 *
 * Identity forwarding:
 *   - VELOCITY_MODERN: backend kicks off a Login Plugin Request on the
 *     "velocity:player_info" channel; we respond with an HMAC-SHA256-signed payload
 *     carrying the real UUID + Mojang properties. Backend (Paper) verifies and uses
 *     the UUID for Login Success.
 *   - BUNGEECORD: handshake address is suffixed with /clientIp/uuid/propertiesJson
 *     so backends with bungeecord=true accept it.
 *   - NONE: backend derives the offline UUID from the username — fine for testing.
 */
public final class BackendConnection {

    private static final Logger log = LoggerFactory.getLogger(BackendConnection.class);

    public interface Listener {
        void onLoginSuccess(BackendConnection backend, ByteBuf loginSuccessPayload);
        void onPlayLoginReceived(BackendConnection backend, ByteBuf playLoginPayload);
        void onPacket(BackendConnection backend, ByteBuf raw);
        void onClosed(BackendConnection backend, String reason);
        void onSetCompression(BackendConnection backend, int threshold);
    }

    private final PrismContext ctx;
    private final Subserver target;
    private final GameProfile profile;
    private final String clientAddress;
    private final int protocolVersion;
    private final PlayPacketIds playIds;
    private final Listener listener;

    private Channel channel;
    private ConnectionState state = ConnectionState.HANDSHAKE;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    public BackendConnection(PrismContext ctx, Subserver target, GameProfile profile,
                             String clientAddress, int protocolVersion, PlayPacketIds playIds,
                             Listener listener) {
        this.ctx = ctx;
        this.target = target;
        this.profile = profile;
        this.clientAddress = clientAddress;
        this.protocolVersion = protocolVersion;
        this.playIds = playIds;
        this.listener = listener;
    }

    public Subserver target() { return target; }
    public Channel channel() { return channel; }
    public ConnectionState state() { return state; }
    public CompletableFuture<Void> readyFuture() { return ready; }

    public CompletableFuture<Void> open() {
        new Bootstrap()
                .group(ctx.workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel ch) {
                        ch.pipeline().addLast("frame", new FrameCodec());
                        ch.pipeline().addLast("backend", new Handler());
                    }
                })
                .connect("127.0.0.1", target.port())
                .addListener((ChannelFuture f) -> {
                    if (!f.isSuccess()) {
                        log.warn("Backend {} connect failed: {}", target.name(), f.cause().toString());
                        ready.completeExceptionally(f.cause());
                        return;
                    }
                    channel = f.channel();
                    sendHandshakeAndLoginStart();
                });
        return ready;
    }

    private void sendHandshakeAndLoginStart() {
        String hsAddress = "127.0.0.1";
        if (ctx.config.forwarding.mode == ForwardingConfig.Mode.BUNGEECORD) {
            hsAddress = "127.0.0.1\0" + clientAddress + "\0"
                    + profile.uuid.toString().replace("-", "") + "\0"
                    + serializePropertiesAsJson(profile);
        }

        ByteBuf hs = channel.alloc().buffer();
        ProtocolUtil.writeVarInt(hs, PacketIds.SB_HANDSHAKE);
        ProtocolUtil.writeVarInt(hs, protocolVersion);
        ProtocolUtil.writeString(hs, hsAddress);
        hs.writeShort(target.port());
        ProtocolUtil.writeVarInt(hs, 2); // login
        channel.writeAndFlush(hs);
        state = ConnectionState.LOGIN;

        ByteBuf ls = channel.alloc().buffer();
        ProtocolUtil.writeVarInt(ls, PacketIds.SB_LOGIN_START);
        ProtocolUtil.writeString(ls, profile.name);
        ProtocolUtil.writeUuid(ls, profile.uuid);
        channel.writeAndFlush(ls);
    }

    public void writeRaw(ByteBuf payload) {
        if (channel != null && channel.isActive()) channel.writeAndFlush(payload);
        else payload.release();
    }

    public void enterConfigState() { state = ConnectionState.CONFIGURATION; }
    public void enterPlayState()   { state = ConnectionState.PLAY; }

    public void close() {
        if (channel != null && channel.isOpen()) channel.close();
    }

    // ---------------- Velocity Modern responder ----------------

    private static final String VELOCITY_CHANNEL = "velocity:player_info";
    /** Velocity modern v1 payload — universally supported by Paper since 1.13. */
    private static final int VELOCITY_FORWARDING_VERSION = 1;

    private void respondToLoginPluginRequest(ChannelHandlerContext c, int messageId, String channelName, ByteBuf data) {
        ForwardingConfig fwd = ctx.config.forwarding;
        boolean handle = fwd.mode == ForwardingConfig.Mode.VELOCITY_MODERN
                && VELOCITY_CHANNEL.equals(channelName)
                && !fwd.velocitySecret.isEmpty();

        if (!handle) {
            ByteBuf out = c.alloc().buffer();
            ProtocolUtil.writeVarInt(out, PacketIds.SB_LOGIN_PLUGIN_RESPONSE);
            ProtocolUtil.writeVarInt(out, messageId);
            out.writeBoolean(false);
            c.writeAndFlush(out);
            return;
        }

        try {
            ByteBuf payload = c.alloc().buffer();
            ProtocolUtil.writeVarInt(payload, VELOCITY_FORWARDING_VERSION);
            ProtocolUtil.writeString(payload, clientAddress);
            ProtocolUtil.writeUuid(payload, profile.uuid);
            ProtocolUtil.writeString(payload, profile.name);
            ProtocolUtil.writeVarInt(payload, profile.properties.size());
            for (GameProfile.Property p : profile.properties) {
                ProtocolUtil.writeString(payload, p.name);
                ProtocolUtil.writeString(payload, p.value);
                if (p.signature != null) {
                    payload.writeBoolean(true);
                    ProtocolUtil.writeString(payload, p.signature);
                } else {
                    payload.writeBoolean(false);
                }
            }

            byte[] payloadBytes = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), payloadBytes);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fwd.velocitySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(payloadBytes);

            ByteBuf out = c.alloc().buffer();
            ProtocolUtil.writeVarInt(out, PacketIds.SB_LOGIN_PLUGIN_RESPONSE);
            ProtocolUtil.writeVarInt(out, messageId);
            out.writeBoolean(true);
            out.writeBytes(hmac);
            out.writeBytes(payloadBytes);
            c.writeAndFlush(out);
            payload.release();
        } catch (Exception e) {
            log.warn("Failed to answer velocity:player_info for {}: {}", profile.name, e.toString());
            ByteBuf out = c.alloc().buffer();
            ProtocolUtil.writeVarInt(out, PacketIds.SB_LOGIN_PLUGIN_RESPONSE);
            ProtocolUtil.writeVarInt(out, messageId);
            out.writeBoolean(false);
            c.writeAndFlush(out);
        }
    }

    /** Encodes the GameProfile properties as a JSON array — only used for BungeeCord legacy forwarding. */
    private static String serializePropertiesAsJson(GameProfile profile) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (GameProfile.Property p : profile.properties) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escape(p.name))
                    .append("\",\"value\":\"").append(escape(p.value)).append('"');
            if (p.signature != null) sb.append(",\"signature\":\"").append(escape(p.signature)).append('"');
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"', '\\' -> b.append('\\').append(c);
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    // ---------------- Netty inbound handler ----------------

    private final class Handler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override protected void channelRead0(ChannelHandlerContext c, ByteBuf msg) {
            int id = ProtocolUtil.readVarInt(msg);
            switch (state) {
                case LOGIN -> handleLogin(c, id, msg);
                case CONFIGURATION -> handleConfig(c, id, msg);
                case PLAY -> handlePlay(c, id, msg);
                default -> log.warn("Backend {} dropped packet id=0x{} in unexpected state {}",
                        target.name(), Integer.toHexString(id), state);
            }
        }

        private void handleLogin(ChannelHandlerContext c, int id, ByteBuf msg) {
            if (id == PacketIds.CB_SET_COMPRESSION) {
                int threshold = ProtocolUtil.readVarInt(msg);
                listener.onSetCompression(BackendConnection.this, threshold);
                if (threshold >= 0) {
                    c.pipeline().addAfter("frame", "compress", new CompressionCodec(threshold));
                }
            } else if (id == PacketIds.CB_LOGIN_SUCCESS) {
                listener.onLoginSuccess(BackendConnection.this, msg);
                ByteBuf ack = c.alloc().buffer();
                ProtocolUtil.writeVarInt(ack, PacketIds.SB_LOGIN_ACK);
                c.writeAndFlush(ack);
                state = ConnectionState.CONFIGURATION;
            } else if (id == PacketIds.CB_LOGIN_DISCONNECT) {
                String reason = "?";
                try { reason = ProtocolUtil.readString(msg, 32767); } catch (Exception ignored) {}
                log.warn("Backend {} login disconnect: {}", target.name(), reason);
                listener.onClosed(BackendConnection.this, "backend login disconnect: " + reason);
                c.close();
            } else if (id == PacketIds.CB_ENCRYPTION_REQ) {
                log.warn("Backend {} requested encryption — subserver must run online-mode=false.", target.name());
                listener.onClosed(BackendConnection.this, "backend requested encryption (subservers must be offline-mode)");
                c.close();
            } else if (id == PacketIds.CB_LOGIN_PLUGIN_REQUEST) {
                int messageId = ProtocolUtil.readVarInt(msg);
                String channelName = ProtocolUtil.readString(msg, 32767);
                respondToLoginPluginRequest(c, messageId, channelName, msg);
            } else {
                log.warn("Backend {} sent unknown LOGIN-state packet id=0x{}", target.name(), Integer.toHexString(id));
            }
        }

        private void handleConfig(ChannelHandlerContext c, int id, ByteBuf msg) {
            ByteBuf raw = c.alloc().buffer(ProtocolUtil.varIntSize(id) + msg.readableBytes());
            ProtocolUtil.writeVarInt(raw, id);
            raw.writeBytes(msg);
            listener.onPacket(BackendConnection.this, raw);
        }

        private void handlePlay(ChannelHandlerContext c, int id, ByteBuf msg) {
            ByteBuf raw = c.alloc().buffer(ProtocolUtil.varIntSize(id) + msg.readableBytes());
            ProtocolUtil.writeVarInt(raw, id);
            raw.writeBytes(msg);
            if (id == playIds.clientboundLogin) {
                ByteBuf payload = raw.duplicate().retain();
                ProtocolUtil.readVarInt(payload); // skip id
                listener.onPlayLoginReceived(BackendConnection.this, payload);
                raw.release();
                return;
            }
            if (id == playIds.clientboundCommands) {
                ByteBuf rewritten = CommandsPacketRewriter.rewrite(c.alloc(), raw, playIds.clientboundCommands);
                if (rewritten != null) {
                    raw.release();
                    listener.onPacket(BackendConnection.this, rewritten);
                    return;
                }
            }
            listener.onPacket(BackendConnection.this, raw);
        }

        @Override public void channelInactive(ChannelHandlerContext c) {
            listener.onClosed(BackendConnection.this, "backend channel inactive");
        }

        @Override public void exceptionCaught(ChannelHandlerContext c, Throwable cause) {
            log.warn("Backend {} error in state {}: {}", target.name(), state, cause.toString());
            c.close();
        }
    }
}
