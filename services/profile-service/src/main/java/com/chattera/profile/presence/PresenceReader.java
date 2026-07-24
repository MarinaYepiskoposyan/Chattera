package com.chattera.profile.presence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads a user's online/offline status from the {@code presence:{userId}}
 * Redis key. ws-gateway owns writing/expiring this key on connect/disconnect
 * (see docs/solution-architecture.md); profile-service only ever reads it.
 * Absence of the key means offline.
 *
 * <p>Presence is a best-effort, soft signal layered on top of durable
 * profile data - a Redis outage must not fail the read/update of that
 * durable data. If Redis is unreachable, this degrades to
 * {@link PresenceStatus#UNKNOWN} rather than propagating the failure.
 */
@Component
public class PresenceReader {

    private static final Logger log = LoggerFactory.getLogger(PresenceReader.class);
    private static final String PRESENCE_KEY_PREFIX = "presence:";

    private final StringRedisTemplate redisTemplate;

    public PresenceReader(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public PresenceStatus readStatus(String userId) {
        try {
            Boolean present = redisTemplate.hasKey(PRESENCE_KEY_PREFIX + userId);
            return Boolean.TRUE.equals(present) ? PresenceStatus.ONLINE : PresenceStatus.OFFLINE;
        } catch (RedisConnectionFailureException redisUnavailable) {
            log.warn("Redis unavailable while reading presence for userId={}; degrading to UNKNOWN", userId,
                    redisUnavailable);
            return PresenceStatus.UNKNOWN;
        }
    }
}
