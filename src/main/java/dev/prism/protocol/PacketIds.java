package dev.prism.protocol;

/**
 * Packet IDs that are STABLE across every 1.21.x protocol (767 / 768 / 769 / ...).
 *
 * The whole HANDSHAKE, STATUS, LOGIN, and CONFIGURATION state tables are stable across the
 * 1.21 line — only the PLAY-state IDs drift between minor versions. PLAY-state IDs are
 * therefore NOT in this file; see {@link PlayPacketIds}, which Prism picks per connection
 * based on the protocol version advertised in the client's handshake.
 *
 * Verified against PrismarineJS/minecraft-data protocol.json for 1.21.1, 1.21.3, and 1.21.11
 * (which share these tables verbatim).
 */
public final class PacketIds {

    private PacketIds() {}

    // ---- Serverbound, HANDSHAKE state ---- (all 1.21.x)
    public static final int SB_HANDSHAKE = 0x00;

    // ---- Serverbound, STATUS state ---- (all 1.21.x)
    public static final int SB_STATUS_REQUEST = 0x00;
    public static final int SB_PING_REQUEST   = 0x01;

    // ---- Clientbound, STATUS state ---- (all 1.21.x)
    public static final int CB_STATUS_RESPONSE = 0x00;
    public static final int CB_PONG_RESPONSE   = 0x01;

    // ---- Serverbound, LOGIN state ---- (all 1.21.x)
    public static final int SB_LOGIN_START              = 0x00;
    public static final int SB_ENCRYPTION_RESPONSE      = 0x01;
    public static final int SB_LOGIN_PLUGIN_RESPONSE    = 0x02;
    public static final int SB_LOGIN_ACK                = 0x03;

    // ---- Clientbound, LOGIN state ---- (all 1.21.x)
    public static final int CB_LOGIN_DISCONNECT     = 0x00;
    public static final int CB_ENCRYPTION_REQ       = 0x01;
    public static final int CB_LOGIN_SUCCESS        = 0x02;
    public static final int CB_SET_COMPRESSION      = 0x03;
    public static final int CB_LOGIN_PLUGIN_REQUEST = 0x04;

    // ---- Clientbound, CONFIGURATION state ---- (all 1.21.x)
    public static final int CB_CFG_DISCONNECT = 0x02;
    public static final int CB_CFG_FINISH     = 0x03;

    // ---- Serverbound, CONFIGURATION state ---- (all 1.21.x)
    public static final int SB_CFG_ACK_FINISH = 0x03; // "Acknowledge Finish Configuration"
}
