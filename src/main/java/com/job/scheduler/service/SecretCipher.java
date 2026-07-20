package com.job.scheduler.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts and decrypts provider/MCP secrets for storage in the database.
 *
 * <p>Voyager stores secret values inline on each row, encrypted with AES-256-GCM.
 * The master key is deployment-owned and supplied out of band via
 * {@code scheduler.secrets.master-key} (env {@code SCHEDULER_SECRETS_MASTER_KEY}),
 * a base64-encoded 32-byte key. The app fails fast at startup if the key is
 * missing or the wrong size, since secrets are required to reach any provider.
 *
 * <p>Stored form is {@code "v1:" + base64(iv || ciphertext||tag)} with a random
 * 12-byte IV per value. The {@code v1} prefix reserves room for future key
 * rotation. GCM's authentication tag makes tampering or a wrong key surface as a
 * decrypt failure rather than garbage plaintext.
 */
@Component
public class SecretCipher {
    private static final String VERSION_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretCipher(@Value("${scheduler.secrets.master-key:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                    "scheduler.secrets.master-key (env SCHEDULER_SECRETS_MASTER_KEY) is required. "
                            + "Generate one with `openssl rand -base64 32`."
            );
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(masterKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "scheduler.secrets.master-key must be base64-encoded", exception
            );
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "scheduler.secrets.master-key must decode to " + KEY_BYTES
                            + " bytes (got " + decoded.length + "). Generate one with "
                            + "`openssl rand -base64 32`."
            );
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** Encrypts a value for storage. Null/blank input returns null (no secret). */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt secret", exception);
        }
    }

    /** Decrypts a stored value. Null/blank input returns null. */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (!stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Unrecognized secret format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            if (combined.length <= IV_BYTES) {
                throw new IllegalStateException("Stored secret is too short");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt secret", exception);
        }
    }
}
