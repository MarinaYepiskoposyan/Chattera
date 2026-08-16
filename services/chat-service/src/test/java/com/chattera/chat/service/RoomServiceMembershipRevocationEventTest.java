package com.chattera.chat.service;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.messaging.ChatEventListener;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.testsupport.ChatDataJpaTest;
import com.chattera.domain.event.DomainEvent;
import com.chattera.domain.event.RoomMembershipRevokedEvent;
import com.chattera.messaging.EventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CHAT-37: exercises the actual "persist-then-publish" wiring end to end -
 * {@code RoomService.leaveRoom} publishing a Spring application event that
 * {@link ChatEventListener}'s {@code AFTER_COMMIT} handler bridges onto the
 * (mocked) Kafka {@link EventPublisher} - against a real transaction
 * manager, rather than unit-testing either class in isolation. A rolled-back
 * leave must never reach the event bus; this is what {@code AFTER_COMMIT}
 * (vs. a plain {@code @EventListener}) guarantees, and is the one behavior
 * that can't be proven by mocking the transaction away.
 *
 * <p>Same {@code @Transactional(NOT_SUPPORTED)} override as
 * {@code RoomServiceConcurrentLeaveTest} - see that class for why - plus an
 * explicit {@link TransactionTemplate} per test method to control commit vs.
 * rollback of the {@code leaveRoom} call itself.
 */
@ChatDataJpaTest
@Import({RoomService.class, ChatEventListener.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomServiceMembershipRevocationEventTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private EventPublisher<DomainEvent> eventPublisher;

    @Test
    void leaveRoomPublishesToTheEventBusAfterTheTransactionCommits() {
        UUID roomId = seedRoomWithMember("user-2");
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> roomService.leaveRoom("user-2", roomId));

        verify(eventPublisher).publish(eq("room-membership-revoked." + roomId), any(RoomMembershipRevokedEvent.class));
    }

    @Test
    void leaveRoomDoesNotPublishToTheEventBusWhenTheTransactionRollsBack() {
        UUID roomId = seedRoomWithMember("user-2");
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            roomService.leaveRoom("user-2", roomId);
            status.setRollbackOnly();
        });

        verifyNoInteractions(eventPublisher);
        // The membership row itself is intact too - the rollback was real.
        org.assertj.core.api.Assertions.assertThat(roomMemberRepository.findByRoomIdAndUserId(roomId, "user-2"))
                .isPresent();
    }

    private UUID seedRoomWithMember(String userId) {
        Room room = roomRepository.save(new Room(UUID.randomUUID(), "general", RoomType.PUBLIC, "owner", Instant.now()));
        roomMemberRepository.save(new RoomMember(room.getId(), userId, RoomRole.MEMBER, Instant.now()));
        return room.getId();
    }
}
