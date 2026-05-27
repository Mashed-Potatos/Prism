package dev.prism.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Persistent RSA keypair used for the Mojang login encryption handshake.
 *
 * Stored at {@code <root>/.prism-keys/rsa-private.der} (PKCS#8 DER). On first boot the
 * file is generated; on subsequent boots it is reused so the public key fingerprint
 * stays stable across restarts.
 *
 * 1024-bit modulus matches what the vanilla Minecraft server uses, which keeps every
 * existing client compatible without forcing larger keys through the encryption-request
 * packet.
 */
public final class KeyManager {

    private static final Logger log = LoggerFactory.getLogger(KeyManager.class);
    private static final int RSA_KEY_SIZE = 1024;

    private final KeyPair keyPair;
    private final byte[] publicKeyEncoded;

    public KeyManager(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.publicKeyEncoded = keyPair.getPublic().getEncoded();
    }

    public KeyPair keyPair()           { return keyPair; }
    public PublicKey publicKey()       { return keyPair.getPublic(); }
    public PrivateKey privateKey()     { return keyPair.getPrivate(); }
    public byte[] publicKeyEncoded()   { return publicKeyEncoded; }

    public static KeyManager loadOrCreate(Path root) throws IOException {
        Path keysDir = root.resolve(".prism-keys");
        Files.createDirectories(keysDir);
        Path keyFile = keysDir.resolve("rsa-private.der");
        KeyPair kp;
        if (Files.isRegularFile(keyFile)) {
            kp = load(keyFile);
            log.info("Loaded RSA keypair from {}", keyFile);
        } else {
            kp = generate();
            save(kp, keyFile);
            log.info("Generated new RSA-{} keypair, saved to {}", RSA_KEY_SIZE, keyFile);
        }
        return new KeyManager(kp);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(RSA_KEY_SIZE);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available in this JVM", e);
        }
    }

    private static KeyPair load(Path file) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(file);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(bytes));
            // Derive public from RSA private key: RSAPrivateCrtKey has the public exponent.
            var rsaPriv = (java.security.interfaces.RSAPrivateCrtKey) priv;
            var pubSpec = new java.security.spec.RSAPublicKeySpec(rsaPriv.getModulus(), rsaPriv.getPublicExponent());
            PublicKey pub = kf.generatePublic(pubSpec);
            return new KeyPair(pub, priv);
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IOException("Failed to load RSA keypair from " + file + " — delete the file to regenerate.", e);
        }
    }

    private static void save(KeyPair kp, Path file) throws IOException {
        Files.write(file, kp.getPrivate().getEncoded());
    }
}
