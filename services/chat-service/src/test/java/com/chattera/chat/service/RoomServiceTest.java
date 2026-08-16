package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.service.exception.NotRoomMemberException;
import com.chattera.chat.service.exception.RoomNotFoundException;
import com.chattera.chat.service.exception.RoomNotSelfJoinableException;
import com.chattera.chat.service.exception.UnsupportedRoomTypeException;
import com.chattera.chat.web.dto.CreateRoomRequest;
import com.chattera.domain.event.RoomMembershipRevokedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    /** Stands in for RoomService's self-proxy - see the class Javadoc on RoomService.self. */
    @Mock
    private RoomService self;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(roomRepository, roomMemberRepository, applicationEventPublisher, self);
    }

    @Test
    void createRoomInsertsTheCreatorAsOwner() {
        CreateRoomRequest request = new CreateRoomRequest("general", RoomType.PUBLIC);
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomWithMembership result = roomService.createRoom("user-1", request);

        assertThat(result.room().getName()).isEqualTo("general");
        assertThat(result.room().getCreatedBy()).isEqualTo("user-1");
        assertThat(result.role()).isEqualTo(RoomRole.OWNER);
        verify(roomMemberRepository).save(argThatOwnerMembership("user-1"));
    }

    @Test
    void createRoomRejectsDirectType() {
        CreateRoomRequest request = new CreateRoomRequest("dm", RoomType.DIRECT);

        assertThatThrownBy(() -> roomService.createRoom("user-1", request))
                .isInstanceOf(UnsupportedRoomTypeException.class);
        verify(roomRepository, never()).save(any());
    }

    @Test
    void joinRoomAddsMembershipForAPublicRoom() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "user-2")).thenReturn(Optional.empty());
        RoomMember inserted = new RoomMember(roomId, "user-2", RoomRole.MEMBER, Instant.now());
        when(self.insertMembership(roomId, "user-2", RoomRole.MEMBER)).thenReturn(inserted);

        RoomWithMembership result = roomService.joinRoom("user-2", roomId);

        assertThat(result.role()).isEqualTo(RoomRole.MEMBER);
        verify(self).insertMembership(roomId, "user-2", RoomRole.MEMBER);
    }

    @Test
    void joinRoomIsIdempotentForAnExistingMember() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        RoomMember existing = new RoomMember(roomId, "user-2", RoomRole.MEMBER, Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "user-2")).thenReturn(Optional.of(existing));

        RoomWithMembership result = roomService.joinRoom("user-2", roomId);

        assertThat(result.role()).isEqualTo(RoomRole.MEMBER);
        verify(self, never()).insertMembership(any(), any(), any());
    }

    @Test
    void joinRoomRejectsPrivateRooms() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "secret", RoomType.PRIVATE, "owner", Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomService.joinRoom("user-2", roomId))
                .isInstanceOf(RoomNotSelfJoinableException.class);
    }

    @Test
    void joinRoomOnUnknownRoomThrowsNotFound() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.joinRoom("user-2", roomId))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void joinRoomLosingTheRaceReadsBackTheWinnersRowInsteadOfThrowing() {
        // Mirrors ProfileService's JIT-provisioning race fix (CHAT-103): this
        // call's own findByRoomIdAndUserId misses, its own insert then hits the
        // other request's already-committed row and throws a duplicate-key
        // violation - the fix reads back the winner's row instead of surfacing
        // that as a bare 500.
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        RoomMember winnersRow = new RoomMember(roomId, "user-2", RoomRole.MEMBER, Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "user-2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(self.insertMembership(roomId, "user-2", RoomRole.MEMBER))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        RoomWithMembership result = roomService.joinRoom("user-2", roomId);

        assertThat(result.role()).isEqualTo(RoomRole.MEMBER);
    }

    @Test
    void leaveRoomRemovesMembership() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        RoomMember membership = new RoomMember(roomId, "user-2", RoomRole.MEMBER, Instant.now());
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "user-2")).thenReturn(Optional.of(membership));

        roomService.leaveRoom("user-2", roomId);

        verify(roomMemberRepository).delete(membership);
        verify(roomMemberRepository, never()).findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(any(), any());
    }

    @Test
    void leaveRoomPublishesAMembershipRevokedEventForTheLeaver() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        RoomMember membership = new RoomMember(roomId, "user-2", RoomRole.MEMBER, Instant.now());
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "user-2")).thenReturn(Optional.of(membership));

        roomService.leaveRoom("user-2", roomId);

        ArgumentCaptor<RoomMembershipRevokedEvent> eventCaptor = ArgumentCaptor.forClass(RoomMembershipRevokedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        RoomMembershipRevokedEvent event = eventCaptor.getValue();
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.userId()).isEqualTo("user-2");
    }

    @Test
    void leaveRoomOnUnknownMembershipThrows() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "not-a-member")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.leaveRoom("not-a-member", roomId))
                .isInstanceOf(NotRoomMemberException.class);
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void ownerLeavingTransfersOwnershipToTheOldestRemainingMember() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        RoomMember ownerMembership = new RoomMember(roomId, "owner", RoomRole.OWNER, Instant.now());
        RoomMember nextOwner = new RoomMember(roomId, "oldest-remaining", RoomRole.MEMBER, Instant.now());
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "owner")).thenReturn(Optional.of(ownerMembership));
        when(roomMemberRepository.findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(roomId, "owner"))
                .thenReturn(Optional.of(nextOwner));

        roomService.leaveRoom("owner", roomId);

        verify(roomMemberRepository).delete(ownerMembership);
        assertThat(nextOwner.getRole()).isEqualTo(RoomRole.OWNER);
        verify(roomMemberRepository).save(nextOwner);
    }

    @Test
    void ownerLeavingAsTheLastMemberLeavesTheRoomWithNoOwner() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "owner", Instant.now());
        RoomMember ownerMembership = new RoomMember(roomId, "owner", RoomRole.OWNER, Instant.now());
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserId(roomId, "owner")).thenReturn(Optional.of(ownerMembership));
        when(roomMemberRepository.findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(roomId, "owner"))
                .thenReturn(Optional.empty());

        roomService.leaveRoom("owner", roomId);

        verify(roomMemberRepository).delete(ownerMembership);
        // No promotion attempted - room row itself is untouched/not deleted.
        verify(roomMemberRepository, never()).save(any());
        verify(roomRepository, never()).delete(any());
    }

    @Test
    void listVisibleRoomsReportsMembershipRolePerRoom() {
        UUID publicRoomId = UUID.randomUUID();
        UUID memberRoomId = UUID.randomUUID();
        Room publicRoom = new Room(publicRoomId, "public", RoomType.PUBLIC, "someone-else", Instant.now());
        Room memberRoom = new Room(memberRoomId, "private", RoomType.PRIVATE, "user-1", Instant.now());
        when(roomRepository.findVisibleToUser("user-1")).thenReturn(List.of(publicRoom, memberRoom));
        when(roomMemberRepository.findByUserId("user-1"))
                .thenReturn(List.of(new RoomMember(memberRoomId, "user-1", RoomRole.OWNER, Instant.now())));

        List<RoomWithMembership> result = roomService.listVisibleRooms("user-1");

        assertThat(result).hasSize(2);
        RoomWithMembership publicEntry = result.stream().filter(r -> r.room().getId().equals(publicRoomId)).findFirst().orElseThrow();
        RoomWithMembership memberEntry = result.stream().filter(r -> r.room().getId().equals(memberRoomId)).findFirst().orElseThrow();
        assertThat(publicEntry.isMember()).isFalse();
        assertThat(memberEntry.isMember()).isTrue();
        assertThat(memberEntry.role()).isEqualTo(RoomRole.OWNER);
    }

    private static RoomMember argThatOwnerMembership(String userId) {
        return org.mockito.ArgumentMatchers.argThat(member ->
                member.getUserId().equals(userId) && member.getRole() == RoomRole.OWNER);
    }
}
