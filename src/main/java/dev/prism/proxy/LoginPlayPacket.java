package dev.prism.proxy;

import dev.prism.protocol.PlayPacketIds;
import dev.prism.protocol.ProtocolUtil;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Login (play) packet parser. Verified against PrismarineJS/minecraft-data for protocols
 * 767 / 768 / 774. We keep the original payload around so we can also forward it verbatim
 * on first join.
 *
 * Protocol drift: starting at 1.21.3 (protocol 768 release/1.21.11 protocol 774), the
 * SpawnInfo block embedded inside Login adds a {@code seaLevel} varint between
 * {@code portalCooldown} and the trailing {@code enforcesSecureChat} flag. The Respawn
 * packet uses the same SpawnInfo block, so this drift is mirrored in
 * {@link RespawnSynthesizer}.
 */
public final class LoginPlayPacket {

    public int entityId;
    public boolean hardcore;
    public List<String> dimensionNames = new ArrayList<>();
    public int maxPlayers;
    public int viewDistance;
    public int simulationDistance;
    public boolean reducedDebugInfo;
    public boolean enableRespawnScreen;
    public boolean doLimitedCrafting;
    public int dimensionType;
    public String dimensionName;
    public long hashedSeed;
    public short gameMode;          // unsigned byte
    public byte previousGameMode;
    public boolean isDebug;
    public boolean isFlat;
    public boolean hasDeathLocation;
    public String deathDimension;
    public long deathLocation;
    public int portalCooldown;
    /** Sea level — present in SpawnInfo since 1.21.3 / protocol 768. Defaults to 64 for older. */
    public int seaLevel = 64;
    public boolean enforcesSecureChat;

    /** True iff the SpawnInfo block carries a seaLevel field (protocol 768+). */
    public static boolean hasSeaLevel(int protocolVersion) {
        return protocolVersion >= PlayPacketIds.PROTOCOL_1_21_2;
    }

    /** Parses payload (positioned AFTER the packet id). Pass the protocol version so the
     *  parser knows whether to expect the SpawnInfo seaLevel field. */
    public static LoginPlayPacket read(ByteBuf in, int protocolVersion) {
        LoginPlayPacket p = new LoginPlayPacket();
        p.entityId = in.readInt();
        p.hardcore = in.readBoolean();
        int dimCount = ProtocolUtil.readVarInt(in);
        for (int i = 0; i < dimCount; i++) p.dimensionNames.add(ProtocolUtil.readString(in, 32767));
        p.maxPlayers = ProtocolUtil.readVarInt(in);
        p.viewDistance = ProtocolUtil.readVarInt(in);
        p.simulationDistance = ProtocolUtil.readVarInt(in);
        p.reducedDebugInfo = in.readBoolean();
        p.enableRespawnScreen = in.readBoolean();
        p.doLimitedCrafting = in.readBoolean();
        p.dimensionType = ProtocolUtil.readVarInt(in);
        p.dimensionName = ProtocolUtil.readString(in, 32767);
        p.hashedSeed = in.readLong();
        p.gameMode = in.readUnsignedByte();
        p.previousGameMode = in.readByte();
        p.isDebug = in.readBoolean();
        p.isFlat = in.readBoolean();
        p.hasDeathLocation = in.readBoolean();
        if (p.hasDeathLocation) {
            p.deathDimension = ProtocolUtil.readString(in, 32767);
            p.deathLocation = in.readLong();
        }
        p.portalCooldown = ProtocolUtil.readVarInt(in);
        if (hasSeaLevel(protocolVersion)) {
            p.seaLevel = ProtocolUtil.readVarInt(in);
        }
        if (in.isReadable()) p.enforcesSecureChat = in.readBoolean();
        return p;
    }
}
