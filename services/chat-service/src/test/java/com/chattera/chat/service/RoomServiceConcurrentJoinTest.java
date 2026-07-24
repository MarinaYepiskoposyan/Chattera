package com.chattera.chat.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.testsupport.ChatDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual database-level race in {@code RoomService.joinRoom}
 * against a real (embedded H2) database: two genuinely concurrent join
 * requests for the same brand-new {@code (roomId, userId)} membership. A
 * single-threaded happy-path test would not catch this, since it never lets
 * two callers both observe an empty {@code findByRoomIdAndUserId} before
 * either commits - same pattern as
 * {@code ProfileServiceConcurrentProvisioningTest} (CHAT-103).
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)} overrides
 * {@code @DataJpaTest}'s default per-test rollback wrapper so the two worker
 * threads get their own independent, genuinely committed transactions.
 */
@ChatDataJpaTest
@Import(RoomService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomServiceConcurrentJoinTest {

    private static final int ITERATIONS = 25;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Test
    void concurrentJoinRequestsForTheSameNewMemberBothSucceedWithExactlyOneRow() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                Room room = roomRepository.save(
                        new Room(UUID.randomUUID(), "room-" + i, RoomType.PUBLIC, "owner-" + i, Instant.now()));
                UUID roomId = room.getId();
                String userId = "joiner-" + i;
                CyclicBarrier barrier = new CyclicBarrier(2);

                Callable<RoomWithMembership> raceJoin = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return roomService.joinRoom(userId, roomId);
                };

                Future<RoomWithMembership> first = executor.submit(raceJoin);
                Future<RoomWithMembership> second = executor.submit(raceJoin);

                // .get() re-throws any exception from the callable (e.g. an
                // unhandled DataIntegrityViolationException) as an
                // ExecutionException, failing the test.
                RoomWithMembership firstResult = first.get(10, TimeUnit.SECONDS);
                RoomWithMembership secondResult = second.get(10, TimeUnit.SECONDS);

                assertThat(firstResult.isMember()).isTrue();
                assertThat(secondResult.isMember()).isTrue();
                assertThat(roomMemberRepository.findByRoomIdAndUserId(roomId, userId)).isPresent();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
