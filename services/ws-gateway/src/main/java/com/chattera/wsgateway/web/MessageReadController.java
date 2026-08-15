package com.chattera.wsgateway.web;

import java.security.Principal;
import java.time.Instant;

import org.springframework.messaging.MessagingException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import com.chattera.domain.event.DomainEvent;
import com.chattera.domain.event.MessageReadEvent;
import com.chattera.messaging.EventPublisher;
import com.chattera.wsgateway.auth.SessionTokenStore;
import com.chattera.wsgateway.membership.RoomMembershipChecker;
import com.chattera.wsgateway.web.dto.MessageReadRequest;

/**
 * Handles STOMP {@code SEND} to {@code /app/message.read} - the client
 * signal that it has rendered messages up to {@code lastReadMessageId} in an
 * open conversation view. Resolves {@code recipientId} from the
 * authenticated session principal (never the body), runs the same
 * subscribe-time-style membership check as {@code StompAuthChannelInterceptor}
 * (cached), and publishes {@link MessageReadEvent}. See
 * solution-architecture.md "What triggers DELIVERED vs READ".
 */
@Controller
public class MessageReadController {

    private final SessionTokenStore sessionTokenStore;
    private final RoomMembershipChecker roomMembershipChecker;
    private final EventPublisher<DomainEvent> eventPublisher;

    public MessageReadController(
            SessionTokenStore sessionTokenStore, RoomMembershipChecker roomMembershipChecker, EventPublisher<DomainEvent> eventPublisher) {
        this.sessionTokenStore = sessionTokenStore;
        this.roomMembershipChecker = roomMembershipChecker;
        this.eventPublisher = eventPublisher;
    }

    @MessageMapping("/message.read")
    public void onMessageRead(
            @Payload MessageReadRequest request,
            Principal principal,
            @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sessionId) {
        if (principal == null) {
            throw new MessagingException("Not authenticated");
        }
        String token = sessionTokenStore.get(sessionId);
        if (token == null || !roomMembershipChecker.isMember(sessionId, request.roomId(), token)) {
            throw new MessagingException("Not authorized to mark messages read in room " + request.roomId());
        }
        eventPublisher.publish("receipt.read", new MessageReadEvent(
                request.lastReadMessageId(), request.roomId(), principal.getName(), Instant.now(), Instant.now()));
    }
}
