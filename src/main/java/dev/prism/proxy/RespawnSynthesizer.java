package dev.prism.proxy;

import dev.prism.protocol.PlayPacketIds;
import dev.prism.protocol.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.List;

/**
 * Builds the Respawn packets used to tear down + relaunch the world on the client
 * during a seamless transfer. Two outputs:
 *   - "Flush" Respawn: forces the client to discard chunks. MUST use a dimension
 *     (name AND type) that differs from BOTH the player's current dimension AND
 *     the destination dimension — otherwise the client treats the respawn as a
 *     same-world event, keeps its chunks, and the subsequent real Respawn into the
 *     destination is also a no-op (since flush already moved it there). The
 *     symptom is a clientside "network protocol error" / disconnect right after
 *     the transfer completes.
 *   - "Real" Respawn: matches the new backend's Login (play) state.
 *
 * The Respawn packet ID itself drifts between 1.21.x; the {@link PlayPacketIds} table
 * resolves it per connection.
 */
public final class RespawnSynthesizer {

    private RespawnSynthesizer() {}

    /** Candidate flush dimensions, in preference order. We deliberately list nether/end
     *  ahead of overworld so the flush is unlikely to ever land on the destination
     *  (which is overworld in the overwhelmingly common case). */
    private static final List<String> FLUSH_NAME_PREFERENCE =
            List.of("minecraft:the_end", "minecraft:the_nether", "minecraft:overworld");

    /** Candidate dimType registry indices to try for the flush. With at most two
     *  values to exclude (current + target) we always find one of {0,1,2}. */
    private static final int[] FLUSH_TYPE_CANDIDATES = new int[]{0, 1, 2};

    public static ByteBuf flushRespawn(ByteBufAllocator alloc, PlayPacketIds ids,
                                       int currentDimType, String currentDimName,
                                       int targetDimType, String targetDimName) {
        String flushName = pickFlushName(currentDimName, targetDimName);
        int flushType = pickFlushType(currentDimType, targetDimType);
        return build(alloc, ids, flushType, flushName, 0L, (short) 0, (byte) -1,
                false, false, false, null, 0L, 0, 64, (byte) 0);
    }

    public static ByteBuf realRespawn(ByteBufAllocator alloc, PlayPacketIds ids,
                                      LoginPlayPacket lp, byte dataKept) {
        return build(alloc, ids, lp.dimensionType, lp.dimensionName, lp.hashedSeed, lp.gameMode,
                lp.previousGameMode, lp.isDebug, lp.isFlat,
                lp.hasDeathLocation, lp.deathDimension, lp.deathLocation, lp.portalCooldown,
                lp.seaLevel, dataKept);
    }

    /** Picks a flush dimension NAME that differs from both current and target. Prefers
     *  the_end then the_nether over overworld so the flush is genuinely distinct. */
    public static String pickFlushName(String currentName, String targetName) {
        for (String candidate : FLUSH_NAME_PREFERENCE) {
            if (!candidate.equals(currentName) && !candidate.equals(targetName)) return candidate;
        }
        // Unreachable: 3 candidates, at most 2 values to exclude.
        return "minecraft:the_end";
    }

    /** Picks a flush dimension TYPE registry index that differs from both current and
     *  target. The registry layout depends on the new backend's configuration, but
     *  indices 0..2 are universally present for vanilla dimension types (overworld /
     *  overworld_caves / the_end). */
    public static int pickFlushType(int currentType, int targetType) {
        for (int candidate : FLUSH_TYPE_CANDIDATES) {
            if (candidate != currentType && candidate != targetType) return candidate;
        }
        return 0; // unreachable; same reasoning
    }

    private static ByteBuf build(ByteBufAllocator alloc, PlayPacketIds ids,
                                 int dimType, String dimName, long seed,
                                 short gameMode, byte prevGameMode,
                                 boolean isDebug, boolean isFlat,
                                 boolean hasDeath, String deathDim, long deathPos,
                                 int portalCooldown, int seaLevel, byte dataKept) {
        ByteBuf out = alloc.buffer();
        ProtocolUtil.writeVarInt(out, ids.clientboundRespawn);
        ProtocolUtil.writeVarInt(out, dimType);
        ProtocolUtil.writeString(out, dimName);
        out.writeLong(seed);
        out.writeByte(gameMode);
        out.writeByte(prevGameMode);
        out.writeBoolean(isDebug);
        out.writeBoolean(isFlat);
        out.writeBoolean(hasDeath);
        if (hasDeath) {
            ProtocolUtil.writeString(out, deathDim);
            out.writeLong(deathPos);
        }
        ProtocolUtil.writeVarInt(out, portalCooldown);
        if (LoginPlayPacket.hasSeaLevel(ids.protocolVersion)) {
            ProtocolUtil.writeVarInt(out, seaLevel);
        }
        out.writeByte(dataKept);
        return out;
    }
}
