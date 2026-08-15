package com.chattera.wsgateway.auth;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.chattera.wsgateway.membership.RoomMembershipChecker;

/**
 * The two authentication/authorization checks CHAT-107 requires on
 * ws-gateway's inbound STOMP channel:
 * <ul>
 *   <li><b>CONNECT</b> - validates the bearer token (via common-security's
 *   shared {@link JwtDecoder}, so the {@code azp} allowlist applies here too),
 *   resolves the principal from the token {@code sub}, and rejects the
 *   CONNECT if the token is missing/invalid.</li>
 *   <li><b>SUBSCRIBE</b> to {@code /topic/rooms.{roomId}} - the one-time
 *   internal membership check against chat-service (cached), rejecting the
 *   frame if the caller isn't a member. See solution-architecture.md
 *   "Real-time delivery - CHAT-107 implementation decisions" §3.</li>
 * </ul>
 * {@code /app/message.read} is deliberately <b>not</b> checked here - its own
 * {@code @MessageMapping} handler runs the same {@link RoomMembershipChecker}
 * check itself, since it needs the room id out of the message body rather
 * than the destination.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final String TOPIC_ROOM_PREFIX = "/topic/rooms.";

    private final JwtDecoder jwtDecoder;
    private final SessionTokenStore sessionTokenStore;
    private final RoomMembershipChecker roomMembershipChecker;

    public StompAuthChannelInterceptor(
            JwtDecoder jwtDecoder, SessionTokenStore sessionTokenStore, RoomMembershipChecker roomMembershipChecker) {
        this.jwtDecoder = jwtDecoder;
        this.sessionTokenStore = sessionTokenStore;
        this.roomMembershipChecker = roomMembershipChecker;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            handleSubscribe(accessor);
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String token = extractBearerToken(accessor);
        if (token == null) {
            throw new MessagingException("Missing bearer token on STOMP CONNECT");
        }
        try {
            Jwt jwt = jwtDecoder.decode(token);
            accessor.setUser(new StompPrincipal(jwt.getSubject()));
            sessionTokenStore.put(accessor.getSessionId(), token);
        } catch (JwtException invalidToken) {
            log.debug("Rejected STOMP CONNECT: invalid token: {}", invalidToken.getMessage());
            throw new MessagingException("Invalid bearer token on STOMP CONNECT", invalidToken);
        }
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        UUID roomId = parseRoomId(accessor.getDestination());
        if (roomId == null) {
            throw new MessagingException("Unknown subscription destination: " + accessor.getDestination());
        }
        String token = sessionTokenStore.get(accessor.getSessionId());
        if (token == null || !roomMembershipChecker.isMember(accessor.getSessionId(), roomId, token)) {
            throw new MessagingException("Not authorized to subscribe to room " + roomId);
        }
    }

    private static String extractBearerToken(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }

    private static UUID parseRoomId(String destination) {
        if (destination == null || !destination.startsWith(TOPIC_ROOM_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(destination.substring(TOPIC_ROOM_PREFIX.length()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
