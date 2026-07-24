package com.chattera.chat.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.testsupport.ChatDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@ChatDataJpaTest
class RoomMemberRepositoryTest {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findFirstByRoomIdAndUserIdNotOrderByJoinedAtAscPicksTheOldestOtherMember() {
        Room room = roomRepository.save(new Room(UUID.randomUUID(), "room", RoomType.PUBLIC, "owner", Instant.now()));
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        roomMemberRepository.save(new RoomMember(room.getId(), "owner", RoomRole.OWNER, base));
        roomMemberRepository.save(new RoomMember(room.getId(), "second-joined", RoomRole.MEMBER, base.plusSeconds(60)));
        roomMemberRepository.save(new RoomMember(room.getId(), "first-joined", RoomRole.MEMBER, base.plusSeconds(30)));

        Optional<RoomMember> nextOwner =
                roomMemberRepository.findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(room.getId(), "owner");

        assertThat(nextOwner).isPresent();
        assertThat(nextOwner.get().getUserId()).isEqualTo("first-joined");
    }

    @Test
    void findFirstByRoomIdAndUserIdNotOrderByJoinedAtAscIsEmptyWhenNoOtherMembersRemain() {
        Room room = roomRepository.save(new Room(UUID.randomUUID(), "room", RoomType.PUBLIC, "owner", Instant.now()));
        roomMemberRepository.save(new RoomMember(room.getId(), "owner", RoomRole.OWNER, Instant.now()));

        Optional<RoomMember> nextOwner =
                roomMemberRepository.findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(room.getId(), "owner");

        assertThat(nextOwner).isEmpty();
    }

    @Test
    void duplicateMembershipViolatesThePrimaryKey() {
        // RoomMember has an assigned (not @GeneratedValue) composite id, so
        // Spring Data's save() issues a merge, not a blind insert: merge
        // always does an existence-check SELECT first and, within the same
        // DB transaction, that SELECT sees the row this test already
        // committed via a flush (same connection = read-your-own-writes) and
        // falls back to an UPDATE - never a conflicting INSERT, no matter
        // how many times save() is called here. That's why the real,
        // production-relevant duplicate-key race (RoomServiceConcurrentJoinTest)
        // only actually surfaces across two separate transactions.
        //
        // To verify the (room_id, user_id) primary key constraint itself
        // exists and is enforced, this test bypasses save()/merge and issues
        // two unconditional inserts directly via the entity manager instead.
        Room room = roomRepository.save(new Room(UUID.randomUUID(), "room", RoomType.PUBLIC, "owner", Instant.now()));
        entityManager.persistAndFlush(new RoomMember(room.getId(), "user-1", RoomRole.MEMBER, Instant.now()));
        entityManager.clear();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        entityManager.persistAndFlush(new RoomMember(room.getId(), "user-1", RoomRole.MEMBER, Instant.now())))
                .isInstanceOf(RuntimeException.class);
    }
}
