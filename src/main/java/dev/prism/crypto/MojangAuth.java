package dev.prism.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Mojang sessionserver hasJoined check. Runs OFF the Netty event loop because
 * the HTTP round-trip is blocking.
 */
public final class MojangAuth {

    private static final Logger log = LoggerFactory.getLogger(MojangAuth.class);
    private static final String HAS_JOINED = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    private final Executor authExecutor;

    public MojangAuth(Executor authExecutor) {
        this.authExecutor = authExecutor;
    }

    public CompletableFuture<GameProfile> hasJoined(String username, String serverHash) {
        CompletableFuture<GameProfile> f = new CompletableFuture<>();
        authExecutor.execute(() -> {
            try {
                String url = HAS_JOINED + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&serverId=" + URLEncoder.encode(serverHash, StandardCharsets.UTF_8);
                HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
                c.setRequestProperty("User-Agent", "Prism/0.5");
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                int code = c.getResponseCode();
                if (code == 204 || code == 403) {
                    f.completeExceptionally(new AuthFailedException("Mojang sessionserver refused (HTTP " + code + ")"));
                    return;
                }
                if (code / 100 != 2) {
                    f.completeExceptionally(new AuthFailedException("Mojang sessionserver returned HTTP " + code));
                    return;
                }
                try (InputStream in = c.getInputStream()) {
                    Object parsed = new Yaml().load(in);
                    f.complete(parseProfile(parsed));
                }
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    @SuppressWarnings("unchecked")
    private static GameProfile parseProfile(Object root) {
        if (!(root instanceof Map<?, ?> map)) throw new AuthFailedException("Malformed hasJoined response (root not an object)");
        String idHex = String.valueOf(map.get("id"));
        String name = String.valueOf(map.get("name"));
        UUID uuid = parseHexUuid(idHex);

        List<GameProfile.Property> properties = new ArrayList<>();
        Object propsRaw = map.get("properties");
        if (propsRaw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> pm)) continue;
                String pname = String.valueOf(pm.get("name"));
                String pvalue = String.valueOf(pm.get("value"));
                Object sig = pm.get("signature");
                properties.add(new GameProfile.Property(pname, pvalue, sig == null ? null : String.valueOf(sig)));
            }
        }
        return new GameProfile(uuid, name, properties);
    }

    /** Mojang returns dashless 32-char hex; UUID.fromString needs dashes. */
    private static UUID parseHexUuid(String hex) {
        if (hex.length() != 32) throw new AuthFailedException("Unexpected UUID length: " + hex);
        String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        return UUID.fromString(dashed);
    }

    public static final class AuthFailedException extends RuntimeException {
        public AuthFailedException(String msg) { super(msg); }
    }
}
