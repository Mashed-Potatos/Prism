package dev.prism.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * Length-prefixed MC framing. Decodes one length-prefixed packet at a time into a fresh ByteBuf.
 * Encodes by writing a VarInt length prefix in front of the packet payload.
 * Compression is handled by {@link CompressionCodec}, installed after Set-Compression is seen.
 */
public final class FrameCodec extends ByteToMessageCodec<ByteBuf> {

    private static final int MAX_PACKET_SIZE = 2 * 1024 * 1024;

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int len = msg.readableBytes();
        ProtocolUtil.writeVarInt(out, len);
        out.writeBytes(msg);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        int peeked = 0;
        int length = 0;
        for (int i = 0; i < 3; i++) {
            if (!in.isReadable()) { in.resetReaderIndex(); return; }
            byte b = in.readByte();
            length |= (b & 0x7F) << (i * 7);
            peeked++;
            if ((b & 0x80) == 0) {
                if (length < 0 || length > MAX_PACKET_SIZE) {
                    throw new CorruptedFrameException("Frame size out of range: " + length);
                }
                if (in.readableBytes() < length) { in.resetReaderIndex(); return; }
                ByteBuf packet = ctx.alloc().buffer(length);
                in.readBytes(packet, length);
                out.add(packet);
                return;
            }
        }
        throw new CorruptedFrameException("VarInt length too large");
    }
}
