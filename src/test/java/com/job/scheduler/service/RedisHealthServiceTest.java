package com.job.scheduler.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisHealthServiceTest {

    @Test
    void reportsAvailableOnlyForPongAndClosesConnection() {
        RedisConnectionFactory factory =
                mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("pong");

        boolean available = new RedisHealthService(factory).isRedisAvailable();

        assertThat(available).isTrue();
        verify(connection).close();
    }

    @Test
    void reportsUnavailableForUnexpectedResponse() {
        RedisConnectionFactory factory =
                mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("LOADING");

        assertThat(new RedisHealthService(factory).isRedisAvailable()).isFalse();
        verify(connection).close();
    }

    @Test
    void reportsUnavailableWhenConnectionFails() {
        RedisConnectionFactory factory =
                mock(RedisConnectionFactory.class);
        when(factory.getConnection())
                .thenThrow(new IllegalStateException("Redis down"));

        assertThat(new RedisHealthService(factory).isRedisAvailable()).isFalse();
    }
}
