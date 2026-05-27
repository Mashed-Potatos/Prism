package dev.prism.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites the clientbound Brigadier "Commands" packet to inject a `/server` literal
 * (with a greedy-string `name` argument child) into the root node's children.
 *
 * Without this, the proxy intercepts `/server` server-side but the client has no
 * client-side knowledge of the command — it never appears in the F3+L list, it
 * doesn't tab-complete, and the chat input shows it red as if it were unknown.
 *
 * <h2>Why this version doesn't walk the full tree</h2>
 *
 * The previous implementation parsed every node end-to-end, advancing past each
 * argument's parser-specific properties using a hand-maintained registry. Whenever
 * Mojang shifted parser ids in a point release, or a plugin registered a custom
 * argument type, the byte-stream walk would misalign and the rewrite would silently
 * fail — taking either /server or the rest of the command tree down with it.
 *
 * This version sidesteps that entirely. It parses ONLY the root node (which has a
 * trivial fixed shape with no name, no parser, no suggestions), copies every other
 * node verbatim, then appends two new nodes (the /server literal and its greedy
 * `name` argument) at the end and patches just the root's child list to reference
 * the new literal.
 *
 * <h2>Assumptions</h2>
 *
 * Two assumptions about Brigadier's serialization let us avoid touching the tail:
 *   1. The root is at node index 0. (Brigadier's BFS serializer adds root first.)
 *   2. The trailing rootIndex VarInt is therefore 0x00 (a single byte).
 *
 * Both are verified before any rewriting happens. If either fails we return null
 * and the caller forwards the original packet unmodified — the same "safe" path
 * the original code took on parse failure, so existing commands always survive.
 *
 * <h2>Packet format (post-id)</h2>
 *   VarInt count
 *   Node[count]
 *   VarInt rootIndex
 *
 * Root node:
 *   byte flags       (bits 0-1 = 0 → root; bit 3 = has redirect)
 *   VarInt childCount
 *   VarInt[childCount] children
 *   VarInt redirect  (only if flag 0x08)
 */
public final class CommandsPacketRewriter {

    private static final Logger log = LoggerFactory.getLogger(CommandsPacketRewriter.class);

    private CommandsPacketRewriter() {}

    /**
     * Reads the Commands packet starting at the packet-id position. Returns a NEW
     * ByteBuf containing the rewritten packet (including the id). The original buffer's
     * reader index is left at the end. On any failure, returns null — the caller
     * should fall back to forwarding the original bytes.
     */
    public static ByteBuf rewrite(ByteBufAllocator alloc, ByteBuf raw, int commandsPacketId) {
        int origReaderIdx = raw.readerIndex();
        int origWriterIdx = raw.writerIndex();
        ByteBuf src = raw.duplicate();
        try {
            int id = ProtocolUtil.readVarInt(src);
            if (id != commandsPacketId) return null;

            int count = ProtocolUtil.readVarInt(src);
            if (count <= 0 || count > 65535) return null;

            // Assumption check: Brigadier's BFS serializer emits root as node 0,
            // so the trailing rootIndex VarInt is 0x00. If the last byte isn't 0x00
            // the assumption doesn't hold for this packet — bail out, the caller
            // forwards the original and all commands remain visible.
            if (raw.getByte(origWriterIdx - 1) != 0x00) return null;

            // Parse ONLY the root node. Per the protocol it has flags + childCount +
            // children + optional redirect, with NO name, NO parser, NO suggestions.
            byte rootFlags = src.readByte();
            if ((rootFlags & 0x03) != 0) return null; // node 0 isn't actually a root
            boolean hasRedirect = (rootFlags & 0x08) != 0;

            int rootChildCount = ProtocolUtil.readVarInt(src);
            if (rootChildCount < 0 || rootChildCount > 65535) return null;
            int childListStart = src.readerIndex();
            for (int k = 0; k < rootChildCount; k++) ProtocolUtil.readVarInt(src);
            int childListEnd = src.readerIndex();
            int redirectEnd = childListEnd;
            if (hasRedirect) {
                ProtocolUtil.readVarInt(src);
                redirectEnd = src.readerIndex();
            }
            int rootEnd = redirectEnd;

            // The rest of the nodes blob runs from rootEnd up to (but not including)
            // the trailing rootIndex byte. We copy that range verbatim — no per-node
            // parsing, no parser-property byte counting, no risk of misalignment.
            int restStart = rootEnd;
            int restEnd = origWriterIdx - 1; // exclusive — final byte is the rootIndex 0x00
            if (restEnd < restStart) return null;

            int newLiteralIndex = count;
            int newArgIndex     = count + 1;

            ByteBuf out = alloc.buffer();
            ProtocolUtil.writeVarInt(out, commandsPacketId);
            ProtocolUtil.writeVarInt(out, count + 2);

            // Re-emit the root: same flags, childCount + 1, original children VarInts,
            // then our new /server literal index as the last child, then the optional
            // redirect VarInt copied verbatim.
            out.writeByte(rootFlags);
            ProtocolUtil.writeVarInt(out, rootChildCount + 1);
            if (childListEnd > childListStart) {
                out.writeBytes(raw, childListStart, childListEnd - childListStart);
            }
            ProtocolUtil.writeVarInt(out, newLiteralIndex);
            if (hasRedirect) {
                out.writeBytes(raw, childListEnd, redirectEnd - childListEnd);
            }

            // Every other node — unmodified, in order.
            if (restEnd > restStart) {
                out.writeBytes(raw, restStart, restEnd - restStart);
            }

            // Appended literal "server": type=1, executable, one child (the arg node).
            out.writeByte(0x01 | 0x04);
            ProtocolUtil.writeVarInt(out, 1);
            ProtocolUtil.writeVarInt(out, newArgIndex);
            ProtocolUtil.writeString(out, "server");

            // Appended argument "name": brigadier:string greedy_phrase, executable, no children.
            out.writeByte(0x02 | 0x04);
            ProtocolUtil.writeVarInt(out, 0);
            ProtocolUtil.writeString(out, "name");
            ProtocolUtil.writeVarInt(out, 5); // brigadier:string parser id
            ProtocolUtil.writeVarInt(out, 2); // greedy_phrase mode

            // rootIndex byte (= 0x00) — unchanged, root is still at node 0.
            out.writeByte(0x00);

            raw.readerIndex(origWriterIdx); // consume original
            return out;
        } catch (Exception e) {
            log.debug("Commands packet rewrite skipped: {}", e.toString());
            return null;
        }
    }

    // For unit-testing convenience: build a tiny Commands packet from scratch.
    public static ByteBuf buildMinimal(int commandsPacketId) {
        ByteBuf b = Unpooled.buffer();
        ProtocolUtil.writeVarInt(b, commandsPacketId);
        ProtocolUtil.writeVarInt(b, 1);
        b.writeByte(0x00);
        ProtocolUtil.writeVarInt(b, 0);
        ProtocolUtil.writeVarInt(b, 0);
        return b;
    }
}
