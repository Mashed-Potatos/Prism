package dev.prism.panel;

import dev.prism.PrismContext;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP listener that serves the admin web panel. Reuses Prism's shared Netty
 * worker group; the boss group is dedicated to this listener and shut down on
 * {@link #shutdown()}.
 */
public final class PanelServer {

    private static final Logger log = LoggerFactory.getLogger(PanelServer.class);

    private final PrismContext ctx;
    private final SessionStore sessions = new SessionStore();
    private EventLoopGroup bossGroup;
    private Channel serverChannel;

    public PanelServer(PrismContext ctx) { this.ctx = ctx; }

    /**
     * Binds the panel listener to the configured host/port. No-op when the panel
     * is disabled or has no configured password.
     */
    public void start() throws InterruptedException {
        if (!ctx.config.panel.enabled) {
            log.info("Panel disabled in config.");
            return;
        }
        if (ctx.config.panel.password == null || ctx.config.panel.password.isBlank()) {
            // Defensive: Prism.main auto-generates and persists a password on empty so this
            // should be unreachable. Skip starting instead of crashing if it ever is hit.
            log.warn("Panel password is unexpectedly empty — skipping panel start.");
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, ctx.workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                        ch.pipeline().addLast(new PanelHandler(ctx, sessions));
                    }
                });
        serverChannel = b.bind(ctx.config.panel.bind, ctx.config.panel.port).sync().channel();
        log.info("Admin panel ready at http://{}:{}/", ctx.config.panel.bind, ctx.config.panel.port);
    }

    /** Closes the panel listener and releases the boss event loop. */
    public void shutdown() {
        if (serverChannel != null) serverChannel.close();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
