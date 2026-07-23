package com.chattera.profile.presence;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads a user's online/offline status from the {@code presence:{userId}}
 * Redis key. ws-gateway owns writing/expiring this key on connect/disconnect
 * (see docs/solution-architecture.md); profile-service only ever reads it.
 * Absence of the key means offline.
 */
@Component
public class PresenceReader {

    private static final String PRESENCE_KEY_PREFIX = "presence:";

    private final StringRedisTemplate redisTemplate;

    public PresenceReader(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public PresenceStatus readStatus(String userId) {
        Boolean present = redisTemplate.hasKey(PRESENCE_KEY_PREFIX + userId);
        return Boolean.TRUE.equals(present) ? PresenceStatus.ONLINE : PresenceStatus.OFFLINE;
    }
}
