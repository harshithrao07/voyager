package com.job.scheduler.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {
    private static final String KEY_A = "xYZP4KD/T0APHH/9GMLiO9vt8D/GFCJXwLzh5ALiGV0=";
    private static final String KEY_B = Base64.getEncoder()
            .encodeToString(new byte[32]); // 32 zero bytes, a different valid key

    private final SecretCipher cipher = new SecretCipher(KEY_A);

    @Test
    void roundTripsAValue() {
        String encrypted = cipher.encrypt("sk-super-secret-123");

        assertThat(encrypted).startsWith("v1:").doesNotContain("sk-super-secret-123");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-super-secret-123");
    }

    @Test
    void producesDistinctCiphertextsForSameInput() {
        // Random IV per call: same plaintext must not encrypt to the same stored form.
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void encryptReturnsNullForNullOrEmpty() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isNull();
    }

    @Test
    void decryptReturnsNullForNullOrBlank() {
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("   ")).isNull();
    }

    @Test
    void decryptRejectsUnknownFormat() {
        assertThatThrownBy(() -> cipher.decrypt("plaintext-no-prefix"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unrecognized secret format");
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        String encrypted = cipher.encrypt("value");
        // Flip a character in the base64 body; GCM's tag must reject it.
        String body = encrypted.substring("v1:".length());
        char first = body.charAt(0);
        String tampered = "v1:" + (first == 'A' ? 'B' : 'A') + body.substring(1);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valueEncryptedWithAnotherKeyDoesNotDecrypt() {
        String encryptedWithB = new SecretCipher(KEY_B).encrypt("value");

        assertThatThrownBy(() -> cipher.decrypt(encryptedWithB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not decrypt secret");
    }

    @Test
    void constructorRejectsMissingKey() {
        assertThatThrownBy(() -> new SecretCipher("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required");
    }

    @Test
    void constructorRejectsNonBase64Key() {
        assertThatThrownBy(() -> new SecretCipher("not base64!!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void constructorRejectsWrongLengthKey() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretCipher(sixteenBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
