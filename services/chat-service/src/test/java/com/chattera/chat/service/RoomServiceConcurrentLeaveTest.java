package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
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
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.testsupport.ChatDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the race {@code RoomService.leaveRoom}'s pessimistic room lock
 * guards against: the OWNER and the next-oldest member leaving at the same
 * instant. Without serializing the two leave transactions, both could read
 * a stale "oldest remaining member" snapshot and either both try to promote
 * the same (also-departing) member, or leave the room with no owner even
 * though a valid remaining member exists. With the lock, whichever leave
 * commits first is fully applied before the other proceeds, so the outcome
 * is deterministic regardless of scheduling order: ownership always lands on
 * the one member who isn't leaving.
 *
 * <p>Same {@code @Transactional(NOT_SUPPORTED)} override as
 * {@code RoomServiceConcurrentJoinTest} - see that class for why.
 */
@ChatDataJpaTest
@Import(RoomService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomServiceConcurrentLeaveTest {

    private static final int ITERATIONS = 15;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Test
    void ownerAndNextOldestMemberLeavingConcurrentlyAlwaysLeavesTheSurvivorAsOwner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                Room room = roomRepository.save(
                        new Room(UUID.randomUUID(), "room-" + i, RoomType.PUBLIC, "owner-" + i, Instant.now()));
                UUID roomId = room.getId();
                String owner = "owner-" + i;
                String middle = "middle-" + i;
                String survivor = "survivor-" + i;
                Instant base = Instant.now();
                roomMemberRepository.save(new RoomMember(roomId, owner, RoomRole.OWNER, base));
                roomMemberRepository.save(new RoomMember(roomId, middle, RoomRole.MEMBER, base.plusSeconds(1)));
                roomMemberRepository.save(new RoomMember(roomId, survivor, RoomRole.MEMBER, base.plusSeconds(2)));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<Void> ownerLeaves = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    roomService.leaveRoom(owner, roomId);
                    return null;
                };
                Callable<Void> middleLeaves = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    roomService.leaveRoom(middle, roomId);
                    return null;
                };

                Future<Void> first = executor.submit(ownerLeaves);
                Future<Void> second = executor.submit(middleLeaves);
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);

                List<RoomMember> remaining = roomMemberRepository.findByUserId(survivor);
                assertThat(remaining).hasSize(1);
                assertThat(remaining.get(0).getRoomId()).isEqualTo(roomId);
                assertThat(remaining.get(0).getRole()).isEqualTo(RoomRole.OWNER);
                assertThat(roomMemberRepository.findByRoomIdAndUserId(roomId, owner)).isEmpty();
                assertThat(roomMemberRepository.findByRoomIdAndUserId(roomId, middle)).isEmpty();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
