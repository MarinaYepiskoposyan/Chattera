package com.chattera.profile.presence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers CHAT-27: presence is a best-effort soft signal, so a Redis outage
 * must degrade to {@link PresenceStatus#UNKNOWN} rather than propagate and
 * fail the caller (GET/PUT /me), which depends on durable profile data that
 * has nothing to do with Redis availability.
 */
@ExtendWith(MockitoExtension.class)
class PresenceReaderTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void returnsOnlineWhenThePresenceKeyExists() {
        when(redisTemplate.hasKey("presence:user-1")).thenReturn(true);

        assertThat(new PresenceReader(redisTemplate).readStatus("user-1")).isEqualTo(PresenceStatus.ONLINE);
    }

    @Test
    void returnsOfflineWhenThePresenceKeyIsAbsent() {
        when(redisTemplate.hasKey("presence:user-1")).thenReturn(false);

        assertThat(new PresenceReader(redisTemplate).readStatus("user-1")).isEqualTo(PresenceStatus.OFFLINE);
    }

    @Test
    void degradesToUnknownInsteadOfThrowingWhenRedisIsUnavailable() {
        when(redisTemplate.hasKey("presence:user-1")).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(new PresenceReader(redisTemplate).readStatus("user-1")).isEqualTo(PresenceStatus.UNKNOWN);
    }
}
