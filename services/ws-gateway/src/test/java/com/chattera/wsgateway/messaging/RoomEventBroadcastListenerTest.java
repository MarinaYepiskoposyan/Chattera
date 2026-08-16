package com.chattera.wsgateway.messaging;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpSubscriptionMatcher;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import com.chattera.domain.event.DomainEvent;
import com.chattera.domain.event.RoomMembershipRevokedEvent;
import com.chattera.messaging.EventPublisher;
import com.chattera.wsgateway.membership.RoomMembershipChecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CHAT-37: {@code onRoomMembershipRevoked} is the trickiest handler on this
 * class - unlike its siblings it must target only the revoked user's own
 * subscription(s) rather than fan out to the room topic. These tests exercise
 * that selectivity directly (mocked {@link SimpUserRegistry} /
 * {@link MessageChannel}, no Spring context needed) - also a first slice of
 * ws-gateway unit coverage generally (CHAT-39 tracks closing the rest).
 */
class RoomEventBroadcastListenerTest {

    private SimpMessagingTemplate messagingTemplate;
    private SimpUserRegistry simpUserRegistry;
    private EventPublisher<DomainEvent> eventPublisher;
    private MessageChannel clientInboundChannel;
    private RoomMembershipChecker roomMembershipChecker;
    private RoomEventBroadcastListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        simpUserRegistry = mock(SimpUserRegistry.class);
        eventPublisher = mock(EventPublisher.class);
        clientInboundChannel = mock(MessageChannel.class);
        roomMembershipChecker = mock(RoomMembershipChecker.class);
        listener = new RoomEventBroadcastListener(
                messagingTemplate, simpUserRegistry, eventPublisher, clientInboundChannel, roomMembershipChecker);
    }

    @Test
    void forceUnsubscribesTheRevokedUsersMatchingSubscriptionAndEvictsTheCacheEntry() {
        UUID roomId = UUID.randomUUID();
        SimpSubscription subscription = subscriptionFor("user-2", "session-1", "sub-42", roomId);
        registryReturns(subscription);

        listener.onRoomMembershipRevoked(new RoomMembershipRevokedEvent(roomId, "user-2", Instant.now()));

        StompHeaderAccessor sentAccessor = capturedUnsubscribeFrame();
        assertThat(sentAccessor.getCommand()).isEqualTo(StompCommand.UNSUBSCRIBE);
        assertThat(sentAccessor.getSessionId()).isEqualTo("session-1");
        assertThat(sentAccessor.getSubscriptionId()).isEqualTo("sub-42");
        verify(roomMembershipChecker).evict("session-1", roomId);
    }

    @Test
    void doesNotFanTheRevocationOutToTheRoomTopic() {
        UUID roomId = UUID.randomUUID();
        SimpSubscription subscription = subscriptionFor("user-2", "session-1", "sub-42", roomId);
        registryReturns(subscription);

        listener.onRoomMembershipRevoked(new RoomMembershipRevokedEvent(roomId, "user-2", Instant.now()));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void noMatchingSubscriptionIsANoOp() {
        UUID roomId = UUID.randomUUID();
        when(simpUserRegistry.findSubscriptions(any())).thenReturn(Set.of());

        listener.onRoomMembershipRevoked(new RoomMembershipRevokedEvent(roomId, "user-2", Instant.now()));

        verifyNoInteractions(clientInboundChannel);
        verifyNoInteractions(roomMembershipChecker);
    }

    @Test
    void everyMatchingSubscriptionForAMultiDeviceUserIsForceUnsubscribedAndEvicted() {
        UUID roomId = UUID.randomUUID();
        SimpSubscription phoneSubscription = subscriptionFor("user-2", "session-phone", "sub-1", roomId);
        SimpSubscription laptopSubscription = subscriptionFor("user-2", "session-laptop", "sub-2", roomId);
        registryReturns(phoneSubscription, laptopSubscription);

        listener.onRoomMembershipRevoked(new RoomMembershipRevokedEvent(roomId, "user-2", Instant.now()));

        verify(clientInboundChannel, org.mockito.Mockito.times(2)).send(any());
        verify(roomMembershipChecker).evict("session-phone", roomId);
        verify(roomMembershipChecker).evict("session-laptop", roomId);
    }

    @Test
    void theFilterPassedToTheRegistryExcludesOtherUsersSubscribedToTheSameRoom() {
        UUID roomId = UUID.randomUUID();
        org.mockito.ArgumentCaptor<SimpSubscriptionMatcher> filterCaptor = org.mockito.ArgumentCaptor.forClass(SimpSubscriptionMatcher.class);
        when(simpUserRegistry.findSubscriptions(filterCaptor.capture())).thenReturn(Set.of());

        listener.onRoomMembershipRevoked(new RoomMembershipRevokedEvent(roomId, "user-2", Instant.now()));

        SimpSubscription otherUsersSubscription = subscriptionFor("someone-else", "session-9", "sub-9", roomId);
        assertThat(filterCaptor.getValue().match(otherUsersSubscription)).isFalse();
    }

    @Test
    void theFilterPassedToTheRegistryExcludesSubscriptionsToOtherRooms() {
        UUID roomId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();
        org.mockito.ArgumentCaptor<SimpSubscriptionMatcher> filterCaptor = org.mockito.ArgumentCaptor.forClass(SimpSubscriptionMatcher.class);
        when(simpUserRegistry.findSubscriptions(filterCaptor.capture())).thenReturn(Set.of());

        listener.onRoomMembershipRevoked(new RoomMembershipRevokedEvent(roomId, "user-2", Instant.now()));

        SimpSubscription differentRoomSubscription = subscriptionFor("user-2", "session-1", "sub-1", otherRoomId);
        assertThat(filterCaptor.getValue().match(differentRoomSubscription)).isFalse();
    }

    private void registryReturns(SimpSubscription... subscriptions) {
        when(simpUserRegistry.findSubscriptions(any())).thenReturn(Set.of(subscriptions));
    }

    private StompHeaderAccessor capturedUnsubscribeFrame() {
        org.mockito.ArgumentCaptor<Message<?>> messageCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(clientInboundChannel).send(messageCaptor.capture());
        return StompHeaderAccessor.wrap(messageCaptor.getValue());
    }

    private static SimpSubscription subscriptionFor(String userId, String sessionId, String subscriptionId, UUID roomId) {
        SimpUser user = mock(SimpUser.class);
        when(user.getName()).thenReturn(userId);
        SimpSession session = mock(SimpSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getUser()).thenReturn(user);
        SimpSubscription subscription = mock(SimpSubscription.class);
        when(subscription.getId()).thenReturn(subscriptionId);
        when(subscription.getSession()).thenReturn(session);
        when(subscription.getDestination()).thenReturn("/topic/rooms." + roomId);
        return subscription;
    }
}
