package dev.prism.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;

/**
 * AES/CFB8 stream cipher pair for Minecraft online-mode connections.
 *
 * Place {@link #inbound(Cipher)} and {@link #outbound(Cipher)} at the HEAD of the
 * pipeline so raw bytes are transformed before any framing or compression. The
 * Cipher instances must be obtained from {@link dev.prism.crypto.EncryptionUtil#aesCfb8}
 * with the same shared secret used for both directions but opposite modes.
 */
public final class CipherCodec {

    private CipherCodec() {}

    public static ByteToMessageDecoder inbound(Cipher decrypt) {
        return new InboundDecoder(decrypt);
    }

    public static MessageToByteEncoder<ByteBuf> outbound(Cipher encrypt) {
        return new OutboundEncoder(encrypt);
    }

    private static final class InboundDecoder extends ByteToMessageDecoder {
        private final Cipher cipher;
        private byte[] heap = new byte[0];

        InboundDecoder(Cipher cipher) { this.cipher = cipher; }

        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, java.util.List<Object> out) {
            int n = in.readableBytes();
            if (n == 0) return;
            if (heap.length < n) heap = new byte[n];
            in.readBytes(heap, 0, n);
            byte[] plain = cipher.update(heap, 0, n);
            if (plain == null || plain.length == 0) return;
            ByteBuf result = ctx.alloc().buffer(plain.length);
            result.writeBytes(plain);
            out.add(result);
        }
    }

    private static final class OutboundEncoder extends MessageToByteEncoder<ByteBuf> {
        private final Cipher cipher;
        private byte[] heap = new byte[0];

        OutboundEncoder(Cipher cipher) { this.cipher = cipher; }

        @Override protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            int n = msg.readableBytes();
            if (n == 0) return;
            if (heap.length < n) heap = new byte[n];
            msg.readBytes(heap, 0, n);
            byte[] cipherText = cipher.update(heap, 0, n);
            if (cipherText != null && cipherText.length > 0) out.writeBytes(cipherText);
        }
    }
}
