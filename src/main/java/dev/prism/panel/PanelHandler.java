package dev.prism.panel;

import dev.prism.PrismContext;
import dev.prism.session.PlayerSession;
import dev.prism.subserver.Subserver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;

/**
 * HTTP dispatcher for the admin panel. Routes static assets, JSON API calls, and
 * WebSocket upgrades for live console tailing.
 */
public final class PanelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(PanelHandler.class);
    private static final String SESSION_COOKIE = "prism_session";

    private final PrismContext ctx;
    private final SessionStore sessions;

    public PanelHandler(PrismContext ctx, SessionStore sessions) {
        this.ctx = ctx;
        this.sessions = sessions;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext c, FullHttpRequest req) {
        String path;
        try { path = new URI(req.uri()).getPath(); } catch (Exception e) { sendStatus(c, BAD_REQUEST); return; }
        if (path == null) path = "/";

        try {
            if (path.startsWith("/ws/console/")) {
                handleWsUpgrade(c, req, path.substring("/ws/console/".length()));
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                sendResource(c, "/panel/index.html", "text/html; charset=utf-8");
                return;
            }
            if (path.startsWith("/static/")) {
                serveStatic(c, path);
                return;
            }
            if (path.startsWith("/api/")) {
                handleApi(c, req, path);
                return;
            }
            sendStatus(c, NOT_FOUND);
        } catch (Exception e) {
            log.warn("Panel request handler failed: {}", e.toString());
            sendStatus(c, INTERNAL_SERVER_ERROR);
        }
    }

    // -------------------- static assets --------------------

    private void serveStatic(ChannelHandlerContext c, String path) {
        String fname = path.substring("/static/".length());
        if (fname.contains("..") || fname.contains("/")) { sendStatus(c, NOT_FOUND); return; }
        String type = switch (fname.substring(fname.lastIndexOf('.') + 1)) {
            case "css" -> "text/css; charset=utf-8";
            case "js"  -> "application/javascript; charset=utf-8";
            default    -> "application/octet-stream";
        };
        sendResource(c, "/panel/" + fname, type);
    }

    private void sendResource(ChannelHandlerContext c, String resource, String contentType) {
        try (InputStream in = PanelHandler.class.getResourceAsStream(resource)) {
            if (in == null) { sendStatus(c, NOT_FOUND); return; }
            byte[] bytes = in.readAllBytes();
            FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, OK, Unpooled.wrappedBuffer(bytes));
            res.headers().set(CONTENT_TYPE, contentType);
            res.headers().set(CONTENT_LENGTH, bytes.length);
            res.headers().set(CACHE_CONTROL, "no-store");
            c.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) { sendStatus(c, INTERNAL_SERVER_ERROR); }
    }

    // -------------------- JSON API --------------------

    private void handleApi(ChannelHandlerContext c, FullHttpRequest req, String path) {
        // Login is unauthenticated; everything else needs a session.
        if (path.equals("/api/login") && req.method() == HttpMethod.POST) { apiLogin(c, req); return; }

        UUID sid = currentSessionId(req);
        if (sid == null || sessions.touch(sid).isEmpty()) { sendStatus(c, UNAUTHORIZED); return; }

        if (path.equals("/api/logout") && req.method() == HttpMethod.POST) { sessions.invalidate(sid); sendJson(c, OK, "{\"ok\":true}"); return; }
        if (path.equals("/api/status") && req.method() == HttpMethod.GET) { apiStatus(c); return; }
        if (path.equals("/api/broadcast") && req.method() == HttpMethod.POST) { apiBroadcast(c, req); return; }
        if (path.startsWith("/api/subserver/")) { apiSubserver(c, req, path); return; }
        if (path.startsWith("/api/player/"))    { apiPlayer(c, req, path); return; }
        sendStatus(c, NOT_FOUND);
    }

    private void apiLogin(ChannelHandlerContext c, FullHttpRequest req) {
        Map<String, Object> body = parseBody(req);
        if (body == null) { sendStatus(c, BAD_REQUEST); return; }
        String u = normalizeCredential(body.get("username"));
        String p = normalizeCredential(body.get("password"));
        String expectedUser = normalizeCredential(ctx.config.panel.username);
        String expectedPass = normalizeCredential(ctx.config.panel.password);
        if (expectedPass.isEmpty() || !expectedUser.equals(u) || !expectedPass.equals(p)) {
            // Tiny throttle to slow down naive brute force.
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            sendStatus(c, UNAUTHORIZED);
            return;
        }
        UUID sid = sessions.create(u);
        Cookie cookie = new DefaultCookie(SESSION_COOKIE, sid.toString());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(24 * 60 * 60);
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, OK,
                Unpooled.wrappedBuffer("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        res.headers().set(CONTENT_TYPE, "application/json");
        res.headers().set("Set-Cookie", ServerCookieEncoder.STRICT.encode(cookie));
        c.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }

    private void apiStatus(ChannelHandlerContext c) {
        StringBuilder b = new StringBuilder("{");

        Map<String, Long> playersBySub = new HashMap<>();
        for (PlayerSession p : ctx.sessions.all()) playersBySub.merge(p.currentSubserver(), 1L, Long::sum);

        b.append("\"subservers\":[");
        boolean first = true;
        for (Subserver s : ctx.subservers.all()) {
            if (!first) b.append(','); first = false;
            b.append("{\"name\":\"").append(Json.esc(s.name())).append("\",")
                    .append("\"port\":").append(s.port()).append(',')
                    .append("\"alive\":").append(s.process().isAlive()).append(',')
                    .append("\"ready\":").append(s.isReady()).append(',')
                    .append("\"players\":").append(playersBySub.getOrDefault(s.name(), 0L))
                    .append('}');
        }
        b.append("],\"players\":[");
        first = true;
        for (PlayerSession p : ctx.sessions.all()) {
            if (!first) b.append(','); first = false;
            b.append("{\"name\":\"").append(Json.esc(p.username())).append("\",")
                    .append("\"uuid\":\"").append(p.uuid()).append("\",")
                    .append("\"subserver\":\"").append(Json.esc(p.currentSubserver())).append("\"}");
        }
        b.append("]}");
        sendJson(c, OK, b.toString());
    }

    private void apiBroadcast(ChannelHandlerContext c, FullHttpRequest req) {
        Map<String, Object> body = parseBody(req);
        if (body == null) { sendStatus(c, BAD_REQUEST); return; }
        Object msg = body.get("message");
        if (!(msg instanceof String s) || s.isBlank()) { sendStatus(c, BAD_REQUEST); return; }
        ctx.chatRouter.broadcastEveryone("[Panel] " + s);
        sendJson(c, OK, "{\"ok\":true}");
    }

    private void apiSubserver(ChannelHandlerContext c, FullHttpRequest req, String path) {
        // /api/subserver/<name>/<action>
        String rest = path.substring("/api/subserver/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) { sendStatus(c, BAD_REQUEST); return; }
        String name = rest.substring(0, slash);
        String action = rest.substring(slash + 1);
        Subserver s = ctx.subservers.get(name).orElse(null);
        if (s == null) { sendStatus(c, NOT_FOUND); return; }

        switch (action) {
            case "command" -> {
                if (req.method() != HttpMethod.POST) { sendStatus(c, METHOD_NOT_ALLOWED); return; }
                Map<String, Object> body = parseBody(req);
                if (body == null || !(body.get("command") instanceof String cmd)) { sendStatus(c, BAD_REQUEST); return; }
                s.process().sendCommand(cmd);
                sendJson(c, OK, "{\"ok\":true}");
            }
            case "start"   -> { ctx.subservers.start(name);   sendJson(c, OK, "{\"ok\":true}"); }
            case "stop"    -> { ctx.subservers.stop(name);    sendJson(c, OK, "{\"ok\":true}"); }
            case "restart" -> { ctx.subservers.restart(name); sendJson(c, OK, "{\"ok\":true}"); }
            default -> sendStatus(c, NOT_FOUND);
        }
    }

    private void apiPlayer(ChannelHandlerContext c, FullHttpRequest req, String path) {
        // /api/player/<name>/transfer
        if (req.method() != HttpMethod.POST) { sendStatus(c, METHOD_NOT_ALLOWED); return; }
        String rest = path.substring("/api/player/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) { sendStatus(c, BAD_REQUEST); return; }
        String name = rest.substring(0, slash);
        String action = rest.substring(slash + 1);
        if (!action.equals("transfer")) { sendStatus(c, NOT_FOUND); return; }
        Map<String, Object> body = parseBody(req);
        if (body == null || !(body.get("target") instanceof String target)) { sendStatus(c, BAD_REQUEST); return; }
        PlayerSession ps = ctx.sessions.find(name).orElse(null);
        if (ps == null) { sendStatus(c, NOT_FOUND); return; }
        ctx.sessions.transfer(ps, target).whenComplete((v, t) -> {
            if (t != null) sendJson(c, BAD_REQUEST, "{\"ok\":false,\"error\":\"" + Json.esc(t.getMessage()) + "\"}");
            else sendJson(c, OK, "{\"ok\":true}");
        });
    }

    // -------------------- WebSocket upgrade --------------------

    private void handleWsUpgrade(ChannelHandlerContext c, FullHttpRequest req, String subName) {
        UUID sid = currentSessionId(req);
        if (sid == null || sessions.touch(sid).isEmpty()) { sendStatus(c, UNAUTHORIZED); return; }
        Subserver sub = ctx.subservers.get(subName).orElse(null);
        if (sub == null) { sendStatus(c, NOT_FOUND); return; }

        String wsUrl = "ws://" + req.headers().get(HOST) + req.uri();
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(wsUrl, null, true);
        WebSocketServerHandshaker hs = factory.newHandshaker(req);
        if (hs == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(c.channel());
            return;
        }
        hs.handshake(c.channel(), req).addListener(f -> {
            if (!f.isSuccess()) { c.close(); return; }
            c.pipeline().replace(PanelHandler.this, "ws-console", new ConsoleWebSocketHandler(sub));
        });
    }

    // -------------------- helpers --------------------

    private UUID currentSessionId(FullHttpRequest req) {
        String header = req.headers().get(COOKIE);
        if (header == null) return null;
        Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(header);
        for (Cookie ck : cookies) {
            if (SESSION_COOKIE.equals(ck.name())) {
                try { return UUID.fromString(ck.value()); } catch (IllegalArgumentException e) { return null; }
            }
        }
        return null;
    }

    /**
     * Normalizes a credential value before comparison. SnakeYAML strips its own quotes, but a
     * password copy-pasted into prism.yml can still arrive with trailing spaces, a stray BOM, or
     * a user-typed pair of surrounding quotes (e.g. {@code password: "'secret'"}). Mirror that on
     * the submitted side too — browser autofill sometimes pads values with whitespace.
     */
    private static String normalizeCredential(Object raw) {
        if (raw == null) return "";
        String s = String.valueOf(raw);
        // Strip a UTF-8 BOM if present, then trim ASCII whitespace.
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        s = s.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(FullHttpRequest req) {
        if (req.content().readableBytes() == 0) return Map.of();
        String body = req.content().toString(StandardCharsets.UTF_8);
        try {
            Object o = new Yaml().load(body);
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (Exception e) { return null; }
    }

    private static void sendStatus(ChannelHandlerContext c, HttpResponseStatus status) {
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        res.headers().set(CONTENT_LENGTH, 0);
        c.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }

    private static void sendJson(ChannelHandlerContext c, HttpResponseStatus status, String json) {
        ByteBuf buf = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        res.headers().set(CONTENT_TYPE, "application/json");
        res.headers().set(CONTENT_LENGTH, buf.readableBytes());
        c.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }
}
