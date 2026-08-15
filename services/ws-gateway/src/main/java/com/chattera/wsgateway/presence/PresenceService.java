package com.chattera.wsgateway.presence;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes/deletes the {@code presence:{userId}} Redis key ws-gateway owns
 * (profile-service's {@code PresenceReader} only ever reads it) - see
 * solution-architecture.md "Presence (unaffected by the IdP choice)": write
 * on connect, expire/delete on disconnect. A generous TTL is set alongside
 * the explicit delete as a safety net (self-heals if DISCONNECT is ever
 * missed - e.g. a crashed pod) - CHAT-24 §5 notes presence "relies on TTL
 * and heartbeat refresh"; a full heartbeat is not built in Sprint 1.
 *
 * <p>Best-effort, mirroring {@code PresenceReader}'s degrade-on-outage
 * stance: a Redis outage must not fail the WebSocket connect/disconnect
 * flow.
 */
@Component
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final String PRESENCE_KEY_PREFIX = "presence:";
    private static final Duration PRESENCE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public PresenceService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void markOnline(String userId) {
        try {
            redisTemplate.opsForValue().set(PRESENCE_KEY_PREFIX + userId, "online", PRESENCE_TTL);
        } catch (RedisConnectionFailureException redisUnavailable) {
            log.warn("Redis unavailable while marking userId={} online", userId, redisUnavailable);
        }
    }

    public void markOffline(String userId) {
        try {
            redisTemplate.delete(PRESENCE_KEY_PREFIX + userId);
        } catch (RedisConnectionFailureException redisUnavailable) {
            log.warn("Redis unavailable while marking userId={} offline", userId, redisUnavailable);
        }
    }
}
