package dev.prism.session;

import dev.prism.config.PrismConfig;
import dev.prism.protocol.Nbt;
import dev.prism.protocol.PlayPacketIds;
import dev.prism.protocol.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-subserver chat fan-out. Each subserver keeps handling chat for its own players
 * (so plugins, formatting, anti-cheat, etc. all still work). Prism additionally
 * synthesizes a System Chat message to players on OTHER subservers that share a chat
 * group with the sender.
 *
 * Multiverse / plugin-channel compatibility: this only fires on the serverbound Chat
 * Message packet. Chat Command, Plugin Message (any channel), and every other packet
 * pass through untouched.
 *
 * System Chat's clientbound packet ID varies by protocol; the recipient's
 * {@link PlayPacketIds} table is used per-write so a mixed-version network works.
 */
public final class ChatRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatRouter.class);

    private final PrismConfig config;
    private final SessionManager sessions;

    public ChatRouter(PrismConfig config, SessionManager sessions) {
        this.config = config;
        this.sessions = sessions;
    }

    public String chatGroupOf(String subserver) {
        for (var e : config.chatGroups.entrySet()) {
            if (e.getValue().contains(subserver)) return e.getKey();
        }
        return null;
    }

    public boolean isGlobal() { return config.chatGroups.isEmpty(); }

    public void onChat(PlayerSession source, String text) {
        String sourceSubserver = source.currentSubserver();
        String sourceChatGroup = chatGroupOf(sourceSubserver);

        for (PlayerSession s : sessions.all()) {
            if (s == source) continue;
            String peer = s.currentSubserver();
            if (peer == null) continue;
            if (peer.equals(sourceSubserver)) continue;

            boolean deliver;
            if (isGlobal()) deliver = true;
            else if (sourceChatGroup == null) deliver = false;
            else deliver = sourceChatGroup.equals(chatGroupOf(peer));

            if (deliver) sendSystemChat(s, "[" + sourceSubserver + "] <" + source.username() + "> " + text);
        }
    }

    public void sendSystemChat(PlayerSession to, String line) {
        Channel ch = to.client().channelCtx().channel();
        if (!ch.isActive()) return;
        ByteBuf out = ch.alloc().buffer();
        ProtocolUtil.writeVarInt(out, to.playIds().clientboundSystemChat);
        Nbt.writeRootString(out, line);
        out.writeBoolean(false); // overlay = false
        ch.writeAndFlush(out);
    }

    public void broadcastEveryone(String line) {
        for (PlayerSession s : sessions.all()) sendSystemChat(s, line);
    }

    /** Extract just the chat text out of a serverbound Chat Message payload (post-id). */
    public static String extractText(ByteBuf payload) {
        return ProtocolUtil.readString(payload, 256);
    }
}
