package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.service.exception.NotRoomMemberException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomMemberServiceTest {

    @Mock
    private RoomAccessService roomAccessService;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    private RoomMemberService roomMemberService;

    @BeforeEach
    void setUp() {
        roomMemberService = new RoomMemberService(roomAccessService, roomMemberRepository);
    }

    @Test
    void listMembersReturnsAllMembersForACaller() {
        UUID roomId = UUID.randomUUID();
        List<RoomMember> members = List.of(
                new RoomMember(roomId, "owner", RoomRole.OWNER, Instant.now()),
                new RoomMember(roomId, "user-2", RoomRole.MEMBER, Instant.now()));
        when(roomMemberRepository.findByRoomIdOrderByJoinedAtAsc(roomId)).thenReturn(members);

        List<RoomMember> result = roomMemberService.listMembers("owner", roomId);

        assertThat(result).isEqualTo(members);
        verify(roomAccessService).requireMembership(roomId, "owner");
    }

    @Test
    void listMembersRejectsNonMembersBeforeTouchingTheRepository() {
        UUID roomId = UUID.randomUUID();
        doThrow(new NotRoomMemberException(roomId)).when(roomAccessService).requireMembership(roomId, "outsider");

        assertThatThrownBy(() -> roomMemberService.listMembers("outsider", roomId))
                .isInstanceOf(NotRoomMemberException.class);
        verify(roomMemberRepository, never()).findByRoomIdOrderByJoinedAtAsc(any());
    }
}
