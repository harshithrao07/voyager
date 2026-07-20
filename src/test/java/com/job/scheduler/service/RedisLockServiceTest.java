package com.job.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisLockServiceTest {
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new RedisLockService(redisTemplate);
        ReflectionTestUtils.setField(lockService, "workerId", "worker-one");
    }

    @Test
    void acquireLockReturnsWorkerScopedTokenWhenSetSucceeds() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("job-lock:job-1"), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(true);

        String token = lockService.acquireLock("job-lock:job-1", Duration.ofSeconds(30));

        assertThat(token).isNotNull().startsWith("worker-one:");
    }

    @Test
    void acquireLockReturnsNullWhenKeyAlreadyHeld() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(false);

        assertThat(lockService.acquireLock("job-lock:job-1", Duration.ofSeconds(30))).isNull();
    }

    @Test
    void acquireLockReturnsNullWhenRedisReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(null);

        assertThat(lockService.acquireLock("job-lock:job-1", Duration.ofSeconds(30))).isNull();
    }

    @Test
    void releaseLockReturnsTrueWhenScriptDeletesOwnedKey() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("job-lock:job-1")), any()))
                .thenReturn(1L);

        assertThat(lockService.releaseLock("job-lock:job-1", "worker-one:token")).isTrue();
    }

    @Test
    void releaseLockReturnsFalseWhenTokenDoesNotMatch() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);

        assertThat(lockService.releaseLock("job-lock:job-1", "someone-else:token")).isFalse();
    }

    @Test
    void renewLockReturnsTrueWhenScriptExtendsOwnedKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertThat(lockService.renewLock("job-lock:job-1", "worker-one:token", Duration.ofSeconds(30)))
                .isTrue();
    }

    @Test
    void renewLockReturnsFalseWhenKeyMissingOrNotOwned() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertThat(lockService.renewLock("job-lock:job-1", "worker-one:token", Duration.ofSeconds(30)))
                .isFalse();
    }
}
