package com.chattera.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.testsupport.ChatDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Flyway migration (V1__create_chat_schema.sql) against
 * an embedded H2 database, with Hibernate ddl-auto left at "validate" (as
 * configured in application.yml) so this also confirms the entity mapping
 * matches the migration.
 */
@ChatDataJpaTest
class RoomRepositoryTest {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Test
    void savesAndReadsBackARoom() {
        Room room = roomRepository.save(new Room(UUID.randomUUID(), "general", RoomType.PUBLIC, "user-1", Instant.now()));

        assertThat(roomRepository.findById(room.getId())).isPresent();
        assertThat(roomRepository.findById(room.getId()).get().getName()).isEqualTo("general");
        assertThat(roomRepository.findById(room.getId()).get().getType()).isEqualTo(RoomType.PUBLIC);
    }

    @Test
    void directRoomNameMayBeNull() {
        Room room = roomRepository.save(new Room(UUID.randomUUID(), null, RoomType.DIRECT, "user-1", Instant.now()));

        assertThat(roomRepository.findById(room.getId())).isPresent();
        assertThat(roomRepository.findById(room.getId()).get().getName()).isNull();
    }

    @Test
    void findVisibleToUserReturnsPublicRoomsRegardlessOfMembership() {
        Room publicRoom = roomRepository.save(new Room(UUID.randomUUID(), "public-room", RoomType.PUBLIC, "owner", Instant.now()));
        Room privateRoom = roomRepository.save(new Room(UUID.randomUUID(), "private-room", RoomType.PRIVATE, "owner", Instant.now()));

        List<Room> visible = roomRepository.findVisibleToUser("someone-not-a-member");

        assertThat(visible).extracting(Room::getId).contains(publicRoom.getId());
        assertThat(visible).extracting(Room::getId).doesNotContain(privateRoom.getId());
    }

    @Test
    void findVisibleToUserIncludesPrivateRoomsTheUserIsAMemberOf() {
        Room privateRoom = roomRepository.save(new Room(UUID.randomUUID(), "private-room", RoomType.PRIVATE, "owner", Instant.now()));
        roomMemberRepository.save(new RoomMember(privateRoom.getId(), "member-1", RoomRole.MEMBER, Instant.now()));

        List<Room> visibleToMember = roomRepository.findVisibleToUser("member-1");
        List<Room> visibleToStranger = roomRepository.findVisibleToUser("stranger");

        assertThat(visibleToMember).extracting(Room::getId).contains(privateRoom.getId());
        assertThat(visibleToStranger).extracting(Room::getId).doesNotContain(privateRoom.getId());
    }

    @Test
    void findByDirectKeyReturnsTheMatchingDirectRoom() {
        Room directRoom = roomRepository.save(
                new Room(UUID.randomUUID(), null, RoomType.DIRECT, "subA", Instant.now(), "subA:subB"));

        assertThat(roomRepository.findByDirectKey("subA:subB")).contains(directRoom);
        assertThat(roomRepository.findByDirectKey("subA:subC")).isEmpty();
    }

    @Test
    void multipleRoomsWithNullDirectKeyAreAllowed() {
        // Plain UNIQUE index on direct_key: PUBLIC/PRIVATE rooms all leave it
        // null, and both Postgres and H2 permit multiple NULLs under a unique
        // constraint - confirms that assumption against the real migration.
        roomRepository.save(new Room(UUID.randomUUID(), "room-1", RoomType.PUBLIC, "owner", Instant.now()));
        roomRepository.save(new Room(UUID.randomUUID(), "room-2", RoomType.PUBLIC, "owner", Instant.now()));

        assertThat(roomRepository.findAll()).hasSize(2);
    }

    @Test
    void findByIdForUpdateLocksAndReturnsTheRoom() {
        Room room = roomRepository.save(new Room(UUID.randomUUID(), "locked-room", RoomType.PUBLIC, "owner", Instant.now()));

        assertThat(roomRepository.findByIdForUpdate(room.getId())).isPresent();
        assertThat(roomRepository.findByIdForUpdate(UUID.randomUUID())).isEmpty();
    }
}
