package dev.prism.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Read/write primitives for the Minecraft network protocol: VarInts, length-prefixed
 * UTF-8 strings, and 128-bit UUIDs. All methods operate on a Netty {@link ByteBuf}
 * and advance its reader or writer index in place.
 */
public final class ProtocolUtil {

    private ProtocolUtil() {}

    /** Reads a Minecraft VarInt (1-5 bytes, 7 bits of payload each). */
    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (!buf.isReadable()) throw new DecoderException("VarInt underflow");
            byte b = buf.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 32) throw new DecoderException("VarInt too large");
        }
    }

    /** Writes a Minecraft VarInt. */
    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }

    /** Returns the number of bytes a Minecraft VarInt encoding of {@code value} occupies. */
    public static int varIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0) return 1;
        if ((value & 0xFFFFC000) == 0) return 2;
        if ((value & 0xFFE00000) == 0) return 3;
        if ((value & 0xF0000000) == 0) return 4;
        return 5;
    }

    /**
     * Reads a length-prefixed UTF-8 string. Rejects strings longer than {@code maxLen}
     * code points (or whose byte length exceeds {@code maxLen * 4}, the worst case for
     * UTF-8) to bound memory consumption from malformed input.
     */
    public static String readString(ByteBuf buf, int maxLen) {
        int len = readVarInt(buf);
        if (len < 0 || len > maxLen * 4) {
            throw new DecoderException("Bad string length: " + len);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.length() > maxLen) throw new DecoderException("String exceeds maxLen " + maxLen);
        return s;
    }

    /** Writes {@code value} as a length-prefixed UTF-8 string. */
    public static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    /** Reads a 128-bit UUID encoded as two big-endian longs (high, low). */
    public static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    /** Writes a 128-bit UUID as two big-endian longs (high, low). */
    public static void writeUuid(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    /**
     * Build the deterministic offline-mode UUID used by vanilla / BungeeCord for unauthenticated joins.
     * Format: OfflinePlayer:<name> hashed via UUID.nameUUIDFromBytes.
     */
    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
