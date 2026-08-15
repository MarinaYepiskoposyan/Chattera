package com.chattera.chat.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.service.exception.RoomNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomAccessServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    private RoomAccessService roomAccessService;

    @BeforeEach
    void setUp() {
        roomAccessService = new RoomAccessService(roomRepository, roomMemberRepository);
    }

    @Test
    void requireSelfMembershipReturnsTheMembershipForAMember() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "room", RoomType.PUBLIC, "owner", Instant.now());
        RoomMember member = new RoomMember(roomId, "user-1", RoomRole.MEMBER, Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "user-1")).thenReturn(Optional.of(member));

        RoomMember result = roomAccessService.requireSelfMembership(roomId, "user-1");

        assertThat(result).isEqualTo(member);
    }

    @Test
    void requireSelfMembershipThrowsNotFoundForANonExistentRoom() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomAccessService.requireSelfMembership(roomId, "user-1"))
                .isInstanceOf(RoomNotFoundException.class);
    }

    /**
     * The anti-enumeration rule: a non-member of an existing room gets the
     * <b>same</b> {@link RoomNotFoundException} (404) as a non-existent room,
     * not the 403 {@code requireMembership} yields.
     */
    @Test
    void requireSelfMembershipThrowsNotFoundNotForbiddenForANonMemberOfAnExistingRoom() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "room", RoomType.PUBLIC, "owner", Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "outsider")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomAccessService.requireSelfMembership(roomId, "outsider"))
                .isInstanceOf(RoomNotFoundException.class);
    }
}
