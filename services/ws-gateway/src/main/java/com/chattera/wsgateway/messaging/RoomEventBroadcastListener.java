package com.chattera.wsgateway.messaging;

import java.time.Instant;
import java.util.Set;

import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.chattera.domain.event.DomainEvent;
import com.chattera.domain.event.MessageDeliveredEvent;
import com.chattera.domain.event.RoomMembershipRevokedEvent;
import com.chattera.domain.event.RoomMessageCreatedEvent;
import com.chattera.domain.event.RoomMessageStatusChangedEvent;
import com.chattera.messaging.EventPublisher;
import com.chattera.wsgateway.membership.RoomMembershipChecker;

/**
 * Consumes this pod's broadcast queue ({@code BroadcastQueueConfig}, bound
 * {@code room.#}) and republishes each event into the local Spring simple
 * broker via {@link SimpMessagingTemplate} - the hand-off point where the
 * pod's own subscription registry takes over delivery filtering (developer
 * does not hand-roll a roomId-&gt;session map; Spring maintains it). See
 * solution-architecture.md "Real-time delivery - CHAT-107 implementation
 * decisions".
 *
 * <p>A single {@code @RabbitListener} class with {@code @RabbitHandler}
 * overloads (mirroring chat-service's {@code ReceiptEventListener}) lets
 * both event types share this pod's one broadcast queue.
 */
@Component
@RabbitListener(queues = "#{wsGatewayBroadcastQueue.name}")
public class RoomEventBroadcastListener {

    private static final String TOPIC_ROOM_PREFIX = "/topic/rooms.";

    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry simpUserRegistry;
    private final EventPublisher<DomainEvent> eventPublisher;
    private final MessageChannel clientInboundChannel;
    private final RoomMembershipChecker roomMembershipChecker;

    public RoomEventBroadcastListener(
            SimpMessagingTemplate messagingTemplate,
            SimpUserRegistry simpUserRegistry,
            EventPublisher<DomainEvent> eventPublisher,
            @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel,
            RoomMembershipChecker roomMembershipChecker) {
        this.messagingTemplate = messagingTemplate;
        this.simpUserRegistry = simpUserRegistry;
        this.eventPublisher = eventPublisher;
        this.clientInboundChannel = clientInboundChannel;
        this.roomMembershipChecker = roomMembershipChecker;
    }

    @RabbitHandler
    public void onRoomMessageCreated(RoomMessageCreatedEvent event) {
        String destination = TOPIC_ROOM_PREFIX + event.roomId();
        messagingTemplate.convertAndSend(destination, event);
        emitDeliveredForLocallyConnectedRecipients(event, destination);
    }

    @RabbitHandler
    public void onRoomMessageStatusChanged(RoomMessageStatusChangedEvent event) {
        messagingTemplate.convertAndSend(TOPIC_ROOM_PREFIX + event.roomId(), event);
    }

    /**
     * CHAT-37: unlike the handlers above, this must <b>not</b>
     * {@code convertAndSend} the event to {@code /topic/rooms.{roomId}} -
     * that would fan a revoke out to every subscriber, leaking who left. It
     * instead targets only the revoked user's own subscription(s) to that
     * room: force-unsubscribes each one via a synthetic STOMP
     * {@code UNSUBSCRIBE} sent through the inbound channel (processed
     * identically to a client-sent UNSUBSCRIBE by the simple broker, leaving
     * the socket and the user's other subscriptions intact) and evicts the
     * matching {@code RoomMembershipChecker} cache entry so a re-SUBSCRIBE
     * within the TTL window re-hits chat-service instead of the stale
     * positive cache. Pods that don't hold the user's socket find no
     * matching subscription and no-op - the broadcast is safe to fan to all
     * pods. See solution-architecture.md §4.
     */
    @RabbitHandler
    public void onRoomMembershipRevoked(RoomMembershipRevokedEvent event) {
        String destination = TOPIC_ROOM_PREFIX + event.roomId();
        Set<SimpSubscription> subscriptions = simpUserRegistry.findSubscriptions(subscription ->
                destination.equals(subscription.getDestination())
                        && subscription.getSession() != null
                        && subscription.getSession().getUser() != null
                        && event.userId().equals(subscription.getSession().getUser().getName()));
        for (SimpSubscription subscription : subscriptions) {
            forceUnsubscribe(subscription);
            roomMembershipChecker.evict(subscription.getSession().getId(), event.roomId());
        }
    }

    private void forceUnsubscribe(SimpSubscription subscription) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId(subscription.getSession().getId());
        accessor.setSubscriptionId(subscription.getId());
        accessor.setLeaveMutable(true);
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
    }

    /**
     * DELIVERED = server-side, automatic: for every user with a local
     * subscription to this room's topic (other than the sender), the message
     * just reached a live socket on this pod, so emit a delivered receipt.
     * {@link SimpUserRegistry#findSubscriptions} queries the same per-pod
     * subscription registry the simple broker itself uses to deliver, so
     * this doesn't require a separately maintained map.
     */
    private void emitDeliveredForLocallyConnectedRecipients(RoomMessageCreatedEvent event, String destination) {
        Set<SimpSubscription> subscriptions =
                simpUserRegistry.findSubscriptions(subscription -> destination.equals(subscription.getDestination()));
        for (SimpSubscription subscription : subscriptions) {
            SimpUser user = subscription.getSession().getUser();
            if (user == null || user.getName().equals(event.senderId())) {
                continue;
            }
            eventPublisher.publish("receipt.delivered", new MessageDeliveredEvent(
                    event.messageId(), event.roomId(), user.getName(), Instant.now(), Instant.now()));
        }
    }
}
