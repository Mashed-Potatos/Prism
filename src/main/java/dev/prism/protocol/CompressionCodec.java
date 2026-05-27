package dev.prism.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * After SetCompression (clientbound, login state), every framed packet becomes:
 *   VarInt  dataLength  (0 = uncompressed; otherwise length of decompressed payload)
 *   data ... (zlib-compressed when dataLength > 0)
 *
 * Place this AFTER FrameCodec in the pipeline so it sees a single MC packet per message.
 */
public final class CompressionCodec extends ByteToMessageCodec<ByteBuf> {

    private final int threshold;
    private final Inflater inflater = new Inflater();
    private final Deflater deflater = new Deflater();

    public CompressionCodec(int threshold) {
        this.threshold = threshold;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int uncompressed = msg.readableBytes();
        if (uncompressed < threshold) {
            ProtocolUtil.writeVarInt(out, 0);
            out.writeBytes(msg);
            return;
        }
        ProtocolUtil.writeVarInt(out, uncompressed);
        byte[] in = new byte[uncompressed];
        msg.readBytes(in);
        deflater.setInput(in);
        deflater.finish();
        byte[] buf = new byte[Math.max(64, uncompressed)];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.writeBytes(buf, 0, n);
        }
        deflater.reset();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) return;
        int dataLength = ProtocolUtil.readVarInt(in);
        if (dataLength == 0) {
            ByteBuf passthrough = ctx.alloc().buffer(in.readableBytes());
            passthrough.writeBytes(in);
            out.add(passthrough);
            return;
        }
        byte[] compressed = new byte[in.readableBytes()];
        in.readBytes(compressed);
        inflater.setInput(compressed);
        byte[] decompressed = new byte[dataLength];
        try {
            int produced = inflater.inflate(decompressed);
            if (produced != dataLength) {
                throw new CorruptedFrameException("Inflated " + produced + ", expected " + dataLength);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new CorruptedFrameException("Zlib decompression failed", e);
        } finally {
            inflater.reset();
        }
        ByteBuf result = ctx.alloc().buffer(dataLength);
        result.writeBytes(decompressed);
        out.add(result);
    }
}
