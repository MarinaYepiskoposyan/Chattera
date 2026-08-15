package com.chattera.wsgateway.presence;

import java.security.Principal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.chattera.wsgateway.auth.SessionTokenStore;
import com.chattera.wsgateway.membership.RoomMembershipChecker;

/**
 * Writes presence on a successfully established STOMP session and cleans up
 * on disconnect. {@link SessionConnectedEvent} (fired after the CONNECTED
 * frame is sent back) is used rather than {@code SessionConnectEvent}
 * (fired before the inbound channel - and therefore
 * {@code StompAuthChannelInterceptor} - has run) specifically because its
 * principal is guaranteed to be the one CHAT-107's CONNECT-time auth set.
 */
@Component
public class WebSocketSessionLifecycleListener {

    private final PresenceService presenceService;
    private final SessionTokenStore sessionTokenStore;
    private final RoomMembershipChecker roomMembershipChecker;

    public WebSocketSessionLifecycleListener(
            PresenceService presenceService, SessionTokenStore sessionTokenStore, RoomMembershipChecker roomMembershipChecker) {
        this.presenceService = presenceService;
        this.sessionTokenStore = sessionTokenStore;
        this.roomMembershipChecker = roomMembershipChecker;
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        Principal user = event.getUser();
        if (user != null) {
            presenceService.markOnline(user.getName());
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        sessionTokenStore.remove(event.getSessionId());
        roomMembershipChecker.evictSession(event.getSessionId());
        Principal user = event.getUser();
        if (user != null) {
            presenceService.markOffline(user.getName());
        }
    }
}
