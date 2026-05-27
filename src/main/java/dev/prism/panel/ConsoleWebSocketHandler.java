package dev.prism.panel;

import dev.prism.subserver.Subserver;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.function.Consumer;

/**
 * One open WebSocket per browser console pane. Pushes the last 200 lines on connect,
 * then streams every new stdout line as a TextWebSocketFrame containing
 * {@code {"line":"…"}}. Client sends {@code {"cmd":"…"}} to pipe a command to the
 * subserver's stdin.
 */
public final class ConsoleWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(ConsoleWebSocketHandler.class);

    private final Subserver sub;
    private Consumer<String> listener;

    public ConsoleWebSocketHandler(Subserver sub) { this.sub = sub; }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // Snapshot of recent output, then live subscription.
        for (String line : sub.process().recentLines()) {
            ctx.writeAndFlush(new TextWebSocketFrame(asLine(line)));
        }
        listener = sub.process().subscribe(line -> {
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new TextWebSocketFrame(asLine(line)));
            }
        });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame text) {
            try {
                Object parsed = new Yaml().load(text.text());
                if (parsed instanceof Map<?, ?> m && m.get("cmd") instanceof String cmd && !cmd.isBlank()) {
                    sub.process().sendCommand(cmd);
                }
            } catch (Exception e) { log.debug("Console WebSocket frame parse failed: {}", e.toString()); }
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (listener != null) sub.process().unsubscribe(listener);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Console WebSocket error: {}", cause.toString());
        ctx.close();
    }

    private static String asLine(String line) {
        return "{\"line\":\"" + Json.esc(line) + "\"}";
    }
}
