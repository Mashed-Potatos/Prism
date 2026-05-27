package dev.prism.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Tiny NBT writer for the "network NBT" form (1.20.3+): a root tag with no name.
 * Only what System Chat content needs — TAG_String. Anything richer can be added
 * as a TAG_Compound here later without changing call sites.
 */
public final class Nbt {

    private Nbt() {}

    public static final byte TAG_END = 0;
    public static final byte TAG_STRING = 8;

    /** Writes an unnamed TAG_String, e.g. the body of a System Chat content field. */
    public static void writeRootString(ByteBuf out, String value) {
        out.writeByte(TAG_STRING);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.writeBytes(bytes);
    }
}
