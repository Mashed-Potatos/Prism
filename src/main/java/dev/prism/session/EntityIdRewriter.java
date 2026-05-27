package dev.prism.session;

import dev.prism.protocol.PlayPacketIds;
import dev.prism.protocol.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Rewrites the player's OWN entity ID inside a small whitelist of clientbound packets where
 * the player's EID is the first VarInt of the payload. After a seamless transfer, the new
 * backend assigns the player a different EID than the client knows itself by; rewriting
 * keeps "you are this entity" packets coherent without touching unrelated entity IDs.
 *
 * Scope: best-effort, not exhaustive. Packets that embed the player's EID deeper in the
 * payload (e.g. Set Passengers, Set Entity Metadata) are not rewritten yet.
 *
 * The whitelist is matched against the per-connection {@link PlayPacketIds} table, since
 * Damage Event / Hurt Animation / Set Camera all shift between 1.21.x minor versions.
 */
public final class EntityIdRewriter {

    private EntityIdRewriter() {}

    private static boolean isLeadingEidPacket(int packetId, PlayPacketIds ids) {
        return packetId == ids.clientboundDamageEvent
                || packetId == ids.clientboundHurtAnimation
                || packetId == ids.clientboundSetCamera;
    }

    /**
     * If the raw clientbound packet's first VarInt (after the packet id) equals backendEid,
     * return a new ByteBuf with it rewritten to clientEid. Otherwise returns {@code raw}.
     */
    public static ByteBuf rewriteClientbound(ByteBufAllocator alloc, ByteBuf raw, PlayPacketIds ids,
                                             int backendEid, int clientEid) {
        if (backendEid == clientEid) return raw;
        int reader = raw.readerIndex();
        int packetId = ProtocolUtil.readVarInt(raw);
        if (!isLeadingEidPacket(packetId, ids)) {
            raw.readerIndex(reader);
            return raw;
        }
        int eidStart = raw.readerIndex();
        int eid = ProtocolUtil.readVarInt(raw);
        if (eid != backendEid) {
            raw.readerIndex(reader);
            return raw;
        }
        int eidLen = raw.readerIndex() - eidStart;
        int tailLen = raw.writerIndex() - raw.readerIndex();

        ByteBuf out = alloc.buffer();
        ProtocolUtil.writeVarInt(out, packetId);
        ProtocolUtil.writeVarInt(out, clientEid);
        out.writeBytes(raw, raw.readerIndex(), tailLen);
        raw.release();
        if (eidLen < 0) throw new AssertionError();
        return out;
    }
}
