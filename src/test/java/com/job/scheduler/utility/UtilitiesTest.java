package com.job.scheduler.utility;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UtilitiesTest {

    @Test
    void getLockKeyPrefixesJobId() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(Utilities.getLockKey(jobId))
                .isEqualTo("job-lock:00000000-0000-0000-0000-000000000001");
    }

    @Test
    void getDoneKeyPrefixesIdempotencyKey() {
        assertThat(Utilities.getDoneKey("abc-123")).isEqualTo("job-done:abc-123");
    }

    @Test
    void getDoneKeyPreservesEmptyIdempotencyKey() {
        assertThat(Utilities.getDoneKey("")).isEqualTo("job-done:");
    }

    @Test
    void constructorIsPrivateAndInvocable() throws Exception {
        Constructor<Utilities> constructor = Utilities.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();

        // Exercise the otherwise-unreachable private constructor for coverage.
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isNotNull();
    }
}
