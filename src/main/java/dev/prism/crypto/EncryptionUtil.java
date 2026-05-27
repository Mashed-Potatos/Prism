package dev.prism.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;

/**
 * Mojang-specific cryptography helpers used by the online-mode login handshake.
 */
public final class EncryptionUtil {

    private EncryptionUtil() {}

    /**
     * Mojang's server hash: SHA-1 over (serverId | sharedSecret | publicKeyEncoded), then
     * interpreted as a signed two's-complement BigInteger and rendered in lowercase hex.
     * Negative digests yield a leading '-' which is intentional and verified server-side.
     */
    public static String serverHash(String serverId, byte[] sharedSecret, byte[] publicKeyEncoded) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            sha.update(sharedSecret);
            sha.update(publicKeyEncoded);
            return new BigInteger(sha.digest()).toString(16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RSA/ECB/PKCS1Padding decrypt — the format Mojang uses for shared-secret transport. */
    public static byte[] rsaDecrypt(PrivateKey privateKey, byte[] cipherText) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, privateKey);
        return c.doFinal(cipherText);
    }

    /** Builds an AES/CFB8 cipher in the requested mode using {@code sharedSecret} as BOTH key and IV. */
    public static Cipher aesCfb8(byte[] sharedSecret, int mode) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
        SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
        c.init(mode, key, new IvParameterSpec(sharedSecret));
        return c;
    }
}
