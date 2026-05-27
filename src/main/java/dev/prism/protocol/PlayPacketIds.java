package dev.prism.protocol;

/**
 * PLAY-state packet IDs that DRIFT between 1.21.x minor versions. Prism resolves the right
 * table from the protocol version advertised in the client's HANDSHAKE and threads the
 * resulting {@link PlayPacketIds} through any code that emits or classifies play-state
 * packets.
 *
 * Verified against PrismarineJS/minecraft-data protocol.json:
 *   protocol 767 -> 1.21 / 1.21.1
 *   protocol 768 -> 1.21.2 / 1.21.3
 *   protocol 774 -> 1.21.11
 *
 * Unknown protocol versions fall back to the closest known table by numeric distance so
 * the proxy still tries something reasonable for forward versions; the actual value used
 * is logged at connection time so operators can spot mismatches.
 */
public final class PlayPacketIds {

    public static final int PROTOCOL_1_21       = 767; // 1.21 / 1.21.1
    public static final int PROTOCOL_1_21_2     = 768; // 1.21.2 / 1.21.3
    public static final int PROTOCOL_1_21_11    = 774; // 1.21.11

    public final int protocolVersion;
    public final String label;

    // ---- Clientbound PLAY ----
    public final int clientboundLogin;            // "Login (play)"
    public final int clientboundRespawn;
    public final int clientboundDisconnect;
    public final int clientboundKeepAlive;
    public final int clientboundStartConfig;      // "Start Configuration"
    public final int clientboundSystemChat;
    public final int clientboundDamageEvent;
    public final int clientboundHurtAnimation;
    public final int clientboundSetCamera;
    public final int clientboundPluginMessage;
    public final int clientboundCommands;         // "Commands" (Brigadier declare_commands)

    // ---- Serverbound PLAY ----
    public final int serverboundKeepAlive;
    public final int serverboundAckConfig;        // "Acknowledge Configuration"
    public final int serverboundChatMessage;      // signed chat (plain text)
    public final int serverboundChatCommand;
    public final int serverboundPluginMessage;

    private PlayPacketIds(int protocolVersion, String label,
                          int cbLogin, int cbRespawn, int cbDisconnect, int cbKeepAlive,
                          int cbStartConfig, int cbSystemChat, int cbDamageEvent,
                          int cbHurtAnimation, int cbSetCamera, int cbPluginMessage,
                          int cbCommands,
                          int sbKeepAlive, int sbAckConfig, int sbChatMessage,
                          int sbChatCommand, int sbPluginMessage) {
        this.protocolVersion = protocolVersion;
        this.label = label;
        this.clientboundLogin = cbLogin;
        this.clientboundRespawn = cbRespawn;
        this.clientboundDisconnect = cbDisconnect;
        this.clientboundKeepAlive = cbKeepAlive;
        this.clientboundStartConfig = cbStartConfig;
        this.clientboundSystemChat = cbSystemChat;
        this.clientboundDamageEvent = cbDamageEvent;
        this.clientboundHurtAnimation = cbHurtAnimation;
        this.clientboundSetCamera = cbSetCamera;
        this.clientboundPluginMessage = cbPluginMessage;
        this.clientboundCommands = cbCommands;
        this.serverboundKeepAlive = sbKeepAlive;
        this.serverboundAckConfig = sbAckConfig;
        this.serverboundChatMessage = sbChatMessage;
        this.serverboundChatCommand = sbChatCommand;
        this.serverboundPluginMessage = sbPluginMessage;
    }

    /** Protocol 767: Minecraft 1.21 and 1.21.1. */
    public static final PlayPacketIds V1_21 = new PlayPacketIds(
            PROTOCOL_1_21, "1.21/1.21.1",
            /* cbLogin           */ 0x2B,
            /* cbRespawn         */ 0x47,
            /* cbDisconnect      */ 0x1D,
            /* cbKeepAlive       */ 0x26,
            /* cbStartConfig     */ 0x69,
            /* cbSystemChat      */ 0x6C,
            /* cbDamageEvent     */ 0x1A,
            /* cbHurtAnimation   */ 0x24,
            /* cbSetCamera       */ 0x52,
            /* cbPluginMessage   */ 0x19,
            /* cbCommands        */ 0x11,
            /* sbKeepAlive       */ 0x18,
            /* sbAckConfig       */ 0x0C,
            /* sbChatMessage     */ 0x06,
            /* sbChatCommand     */ 0x04,
            /* sbPluginMessage   */ 0x12);

    /** Protocol 768: Minecraft 1.21.2 and 1.21.3. */
    public static final PlayPacketIds V1_21_2 = new PlayPacketIds(
            PROTOCOL_1_21_2, "1.21.2/1.21.3",
            /* cbLogin           */ 0x2C,
            /* cbRespawn         */ 0x4C,
            /* cbDisconnect      */ 0x1D,
            /* cbKeepAlive       */ 0x27,
            /* cbStartConfig     */ 0x70,
            /* cbSystemChat      */ 0x73,
            /* cbDamageEvent     */ 0x1A,
            /* cbHurtAnimation   */ 0x25,
            /* cbSetCamera       */ 0x57,
            /* cbPluginMessage   */ 0x19,
            /* cbCommands        */ 0x11,
            /* sbKeepAlive       */ 0x1A,
            /* sbAckConfig       */ 0x0E,
            /* sbChatMessage     */ 0x07,
            /* sbChatCommand     */ 0x05,
            /* sbPluginMessage   */ 0x14);

    /** Protocol 774: Minecraft 1.21.11. */
    public static final PlayPacketIds V1_21_11 = new PlayPacketIds(
            PROTOCOL_1_21_11, "1.21.11",
            /* cbLogin           */ 0x30,
            /* cbRespawn         */ 0x50,
            /* cbDisconnect      */ 0x20,
            /* cbKeepAlive       */ 0x2B,
            /* cbStartConfig     */ 0x74,
            /* cbSystemChat      */ 0x77,
            /* cbDamageEvent     */ 0x19,
            /* cbHurtAnimation   */ 0x29,
            /* cbSetCamera       */ 0x5B,
            /* cbPluginMessage   */ 0x18,
            /* cbCommands        */ 0x10,
            /* sbKeepAlive       */ 0x1B,
            /* sbAckConfig       */ 0x0F,
            /* sbChatMessage     */ 0x08,
            /* sbChatCommand     */ 0x06,
            /* sbPluginMessage   */ 0x15);

    /**
     * Picks the table for an advertised protocol version. Exact-match preferred; otherwise
     * the nearest known version is used as a best effort, and the caller is expected to log
     * the fallback so operators can notice.
     */
    public static PlayPacketIds forProtocol(int protocolVersion) {
        return switch (protocolVersion) {
            case PROTOCOL_1_21    -> V1_21;
            case PROTOCOL_1_21_2  -> V1_21_2;
            case PROTOCOL_1_21_11 -> V1_21_11;
            default -> {
                if (protocolVersion < PROTOCOL_1_21_2) yield V1_21;
                if (protocolVersion < PROTOCOL_1_21_11) yield V1_21_2;
                yield V1_21_11;
            }
        };
    }

    @Override public String toString() { return "PlayPacketIds{" + label + " proto=" + protocolVersion + "}"; }
}
