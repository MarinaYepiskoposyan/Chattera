package com.chattera.wsgateway.membership;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.chattera.wsgateway.config.WsGatewayProperties;

/**
 * ws-gateway's one-time internal membership check against chat-service
 * (CHAT-107 "Subscribe-time authorization"): a Spring {@code ChannelInterceptor}
 * calls this at STOMP {@code SUBSCRIBE} time, and {@code MessageReadController}
 * calls it before publishing a read receipt - never on the message delivery
 * hot path. Forwards the connected user's own bearer token (captured at
 * CONNECT); no service account / confidential client.
 *
 * <p>Caches a positive {@code (sessionId, roomId)} result for ~60s to absorb
 * reconnect/re-subscribe storms, per solution-architecture.md. A negative
 * result is never cached - it's cheap to re-check and caching "no access"
 * would delay a legitimate retry (e.g. after the caller is actually added to
 * the room).
 */
@Component
public class RoomMembershipChecker {

    private static final Logger log = LoggerFactory.getLogger(RoomMembershipChecker.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final Map<String, Instant> positiveResultExpiryByCacheKey = new ConcurrentHashMap<>();

    public RoomMembershipChecker(RestClient.Builder restClientBuilder, WsGatewayProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getChatServiceBaseUrl()).build();
    }

    public boolean isMember(String sessionId, UUID roomId, String bearerToken) {
        String cacheKey = cacheKey(sessionId, roomId);
        Instant cachedExpiry = positiveResultExpiryByCacheKey.get(cacheKey);
        if (cachedExpiry != null && cachedExpiry.isAfter(Instant.now())) {
            return true;
        }

        try {
            restClient.get()
                    .uri("/rooms/{roomId}/members/me", roomId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .toBodilessEntity();
            positiveResultExpiryByCacheKey.put(cacheKey, Instant.now().plus(CACHE_TTL));
            return true;
        } catch (RestClientResponseException notAMemberOrUnauthorized) {
            return false;
        } catch (Exception chatServiceUnreachable) {
            log.warn("chat-service membership check failed for room={}: {}", roomId, chatServiceUnreachable.getMessage());
            return false;
        }
    }

    /**
     * Called on disconnect ({@code WebSocketSessionLifecycleListener}) so a
     * stale session's cached grants don't linger past its lifetime.
     */
    public void evictSession(String sessionId) {
        positiveResultExpiryByCacheKey.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
    }

    /**
     * Called when {@code RoomEventBroadcastListener} force-drops a live
     * subscription on {@link com.chattera.domain.event.RoomMembershipRevokedEvent}
     * (CHAT-37). Removes only the single {@code (sessionId, roomId)} cache
     * entry, not the whole session ({@link #evictSession}) - the session's
     * other room grants are still valid. Mandatory: without it, a
     * just-revoked client that re-SUBSCRIBEs within the ~60s TTL would be
     * re-admitted from the stale positive cache instead of re-hitting
     * chat-service (which now correctly returns 404).
     */
    public void evict(String sessionId, UUID roomId) {
        positiveResultExpiryByCacheKey.remove(cacheKey(sessionId, roomId));
    }

    private static String cacheKey(String sessionId, UUID roomId) {
        return sessionId + ":" + roomId;
    }
}
