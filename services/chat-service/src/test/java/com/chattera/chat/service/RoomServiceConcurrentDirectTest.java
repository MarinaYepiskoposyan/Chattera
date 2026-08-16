package com.chattera.chat.service;

import java.util.List;
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
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.testsupport.ChatDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual database-level race in
 * {@code RoomService.findOrCreateDirect} against a real (embedded H2)
 * database: two genuinely concurrent find-or-create requests for the same
 * brand-new user pair. A single-threaded happy-path test would not catch
 * this, since it never lets two callers both observe an empty
 * {@code findByDirectKey} before either commits - same pattern as
 * {@code RoomServiceConcurrentJoinTest} (CHAT-104) and
 * {@code ProfileServiceConcurrentProvisioningTest} (CHAT-103).
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)} overrides
 * {@code @DataJpaTest}'s default per-test rollback wrapper so the two worker
 * threads get their own independent, genuinely committed transactions.
 */
@ChatDataJpaTest
@Import(RoomService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomServiceConcurrentDirectTest {

    private static final int ITERATIONS = 25;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Test
    void concurrentFindOrCreateForTheSamePairAlwaysConvergesOnExactlyOneRoom() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                String subA = "subA-" + i;
                String subB = "subB-" + i;
                CyclicBarrier barrier = new CyclicBarrier(2);

                Callable<DirectRoomOutcome> raceFromA = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return roomService.findOrCreateDirect(subA, subB);
                };
                Callable<DirectRoomOutcome> raceFromB = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return roomService.findOrCreateDirect(subB, subA);
                };

                Future<DirectRoomOutcome> first = executor.submit(raceFromA);
                Future<DirectRoomOutcome> second = executor.submit(raceFromB);

                // .get() re-throws any exception from the callable (e.g. an
                // unhandled DataIntegrityViolationException) as an
                // ExecutionException, failing the test.
                DirectRoomOutcome firstResult = first.get(10, TimeUnit.SECONDS);
                DirectRoomOutcome secondResult = second.get(10, TimeUnit.SECONDS);

                assertThat(firstResult.roomWithMembership().room().getId())
                        .isEqualTo(secondResult.roomWithMembership().room().getId());
                // Exactly one of the two racers actually created the room.
                assertThat(firstResult.created() ^ secondResult.created()).isTrue();

                List<RoomMember> members = roomMemberRepository.findByRoomIdOrderByJoinedAtAsc(
                        firstResult.roomWithMembership().room().getId());
                assertThat(members).hasSize(2);
                assertThat(members).extracting(RoomMember::getUserId).containsExactlyInAnyOrder(subA, subB);

                Room room = roomRepository.findByDirectKey(
                        subA.compareTo(subB) <= 0 ? subA + ":" + subB : subB + ":" + subA).orElseThrow();
                assertThat(room.getType()).isEqualTo(RoomType.DIRECT);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
