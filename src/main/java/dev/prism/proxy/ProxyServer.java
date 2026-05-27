package dev.prism.proxy;

import dev.prism.PrismContext;
import dev.prism.protocol.FrameCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public-facing TCP listener that clients connect to. Each accepted connection
 * gets a fresh {@link ClientHandler} which drives the full Minecraft handshake +
 * login + play lifecycle for that player.
 */
public final class ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    private final PrismContext ctx;
    private EventLoopGroup bossGroup;
    private Channel serverChannel;

    public ProxyServer(PrismContext ctx) {
        this.ctx = ctx;
    }

    /** Binds the proxy to the configured host/port. Blocks until the bind completes. */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, ctx.workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel ch) {
                        ch.pipeline().addLast("frame", new FrameCodec());
                        ch.pipeline().addLast("client", new ClientHandler(ctx));
                    }
                });
        serverChannel = b.bind(ctx.config.bindHost, ctx.config.bindPort).sync().channel();
        log.info("Prism proxy listening on {}:{}", ctx.config.bindHost, ctx.config.bindPort);
    }

    /** Closes the listening socket and shuts down the boss event loop. */
    public void shutdown() {
        if (serverChannel != null) serverChannel.close();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
