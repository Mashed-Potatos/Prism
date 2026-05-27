package dev.prism;

import dev.prism.config.PrismConfig;
import dev.prism.crypto.KeyManager;
import dev.prism.crypto.MojangAuth;
import dev.prism.session.ChatRouter;
import dev.prism.session.SessionManager;
import dev.prism.subserver.SubserverManager;
import io.netty.channel.EventLoopGroup;

/**
 * Aggregate of long-lived services shared by every component in Prism.
 *
 * <p>Holds references to the parsed configuration, the subserver registry, the
 * session table, Netty's worker {@link EventLoopGroup}, the cross-subserver chat
 * router, the persistent RSA key material, and the Mojang authentication client.
 *
 * <p>Constructed once at boot and passed by reference; fields are final and never
 * reassigned after construction.
 */
public final class PrismContext {

    public final PrismConfig config;
    public final SubserverManager subservers;
    public final SessionManager sessions;
    public final EventLoopGroup workerGroup;
    public final ChatRouter chatRouter;
    public final KeyManager keys;
    public final MojangAuth mojang;

    public PrismContext(PrismConfig config, SubserverManager subservers, SessionManager sessions,
                        EventLoopGroup workerGroup, ChatRouter chatRouter,
                        KeyManager keys, MojangAuth mojang) {
        this.config = config;
        this.subservers = subservers;
        this.sessions = sessions;
        this.workerGroup = workerGroup;
        this.chatRouter = chatRouter;
        this.keys = keys;
        this.mojang = mojang;
    }
}
