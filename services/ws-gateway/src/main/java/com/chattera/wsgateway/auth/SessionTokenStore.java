package com.chattera.wsgateway.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Holds each live STOMP session's bearer token, captured once at CONNECT
 * ({@link StompAuthChannelInterceptor}) and reused for the subscribe-time/
 * read membership check ({@code RoomMembershipChecker} forwards it to
 * chat-service) - "the user's own access token (captured at CONNECT)", per
 * solution-architecture.md. Never refreshed mid-session (see that section's
 * noted post-Sprint-1 hardening); removed on disconnect.
 */
@Component
public class SessionTokenStore {

    private final Map<String, String> tokensBySessionId = new ConcurrentHashMap<>();

    public void put(String sessionId, String token) {
        tokensBySessionId.put(sessionId, token);
    }

    public String get(String sessionId) {
        return tokensBySessionId.get(sessionId);
    }

    public void remove(String sessionId) {
        tokensBySessionId.remove(sessionId);
    }
}
