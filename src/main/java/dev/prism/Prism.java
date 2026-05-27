package dev.prism;

import dev.prism.config.PrismConfig;
import dev.prism.console.PrismConsole;
import dev.prism.crypto.KeyManager;
import dev.prism.crypto.MojangAuth;
import dev.prism.panel.PanelServer;
import dev.prism.proxy.ProxyServer;
import dev.prism.session.ChatRouter;
import dev.prism.session.SessionManager;
import dev.prism.subserver.SubserverManager;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for the Prism Minecraft proxy.
 *
 * <p>Boots all long-lived services in the following order:
 * <ol>
 *   <li>Load (or create) {@code prism.yml} from the working directory.</li>
 *   <li>Auto-generate the admin panel password and the velocity-modern forwarding
 *       secret on first boot, persisting both back to disk.</li>
 *   <li>Load (or create) the persistent RSA keypair used for the Mojang login
 *       encryption handshake.</li>
 *   <li>Discover and launch backend subservers.</li>
 *   <li>Start the proxy listener and the admin web panel.</li>
 *   <li>Hand control to the interactive console reader.</li>
 * </ol>
 *
 * <p>Shutdown is coordinated through a single idempotent runnable that is invoked
 * either by the {@code stopall} console command or by the JVM shutdown hook.
 */
public final class Prism {

    private static final Logger log = LoggerFactory.getLogger(Prism.class);

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("prism.dir", ".")).toAbsolutePath().normalize();

        Path cfgFile = root.resolve("prism.yml");
        PrismConfig cfg = PrismConfig.loadOrCreate(cfgFile);

        if (cfg.panel.enabled && (cfg.panel.password == null || cfg.panel.password.isBlank())) {
            String generated = generatePanelPassword();
            cfg.panel.password = generated;
            try {
                PrismConfig.writePanelPassword(cfgFile, generated);
                log.info("Panel password generated: {} (saved to prism.yml)", generated);
            } catch (java.io.IOException e) {
                log.warn("Panel password generated: {} (could not persist to prism.yml: {})",
                        generated, e.toString());
            }
        }

        if (cfg.forwarding.mode == dev.prism.config.ForwardingConfig.Mode.VELOCITY_MODERN
                && (cfg.forwarding.velocitySecret == null || cfg.forwarding.velocitySecret.isBlank())) {
            String generated = generateVelocitySecret();
            cfg.forwarding.velocitySecret = generated;
            try {
                PrismConfig.writeVelocitySecret(cfgFile, generated);
            } catch (java.io.IOException e) {
                log.warn("velocity-modern secret generated but could not persist to prism.yml: {}",
                        e.toString());
            }
        }

        log.info("Prism starting on {}:{} (forwarding={})",
                cfg.bindHost, cfg.bindPort, cfg.forwarding.mode);

        KeyManager keys = KeyManager.loadOrCreate(root);
        ThreadPoolExecutor authPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "mojang-auth"); t.setDaemon(true); return t;
        });
        MojangAuth mojang = new MojangAuth(authPool);

        EventLoopGroup workers = new NioEventLoopGroup();
        SessionManager sessions = new SessionManager();
        SubserverManager subservers = new SubserverManager(root, cfg);

        ChatRouter chatRouter = new ChatRouter(cfg, sessions);
        PrismContext ctx = new PrismContext(cfg, subservers, sessions, workers, chatRouter, keys, mojang);
        sessions.attach(ctx);

        Set<Integer> usedPorts = new HashSet<>();
        subservers.discoverAndLaunch(usedPorts);

        ProxyServer proxy = new ProxyServer(ctx);
        proxy.start();

        PanelServer panel = new PanelServer(ctx);
        try { panel.start(); }
        catch (Exception e) { log.warn("Panel failed to start: {}", e.toString()); }

        AtomicBoolean stopping = new AtomicBoolean();
        Runnable stopAll = () -> {
            if (!stopping.compareAndSet(false, true)) return;
            log.info("Shutting down Prism");
            proxy.shutdown();
            panel.shutdown();
            subservers.shutdownAll();
            workers.shutdownGracefully();
            authPool.shutdown();
            try { authPool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            new Thread(() -> { try { Thread.sleep(500); } catch (InterruptedException ignored) {} System.exit(0); }).start();
        };

        new PrismConsole(ctx, stopAll).start();

        Runtime.getRuntime().addShutdownHook(new Thread(stopAll, "prism-shutdown"));
    }

    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static String generatePanelPassword() {
        SecureRandom rng = new SecureRandom();
        char[] out = new char[12];
        for (int i = 0; i < out.length; i++) out[i] = PASSWORD_ALPHABET[rng.nextInt(PASSWORD_ALPHABET.length)];
        return new String(out);
    }

    /** 64-char alphanumeric — enough entropy for HMAC-SHA256, fits in YAML on one line. */
    private static String generateVelocitySecret() {
        SecureRandom rng = new SecureRandom();
        char[] out = new char[64];
        for (int i = 0; i < out.length; i++) out[i] = PASSWORD_ALPHABET[rng.nextInt(PASSWORD_ALPHABET.length)];
        return new String(out);
    }
}
