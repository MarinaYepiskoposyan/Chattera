package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.testsupport.ChatDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHAT-105 AC-15: RoomMemberService's listing path never branches on
 * RoomType (confirmed by inspection), but no prior test drove a real
 * RoomType.DIRECT room through it - RoomMemberServiceTest mocks
 * RoomAccessService wholesale, so a Room's type never reaches the code under
 * test there. Uses the same {@code @ChatDataJpaTest} + {@code @Import}
 * pattern as MessageServiceDirectRoomTest, against a genuine DIRECT room
 * row, to confirm both DM participants resolve via
 * {@code GET /rooms/{roomId}/members} - the mechanism a client uses to look
 * up "the other participant" for DM display.
 */
@ChatDataJpaTest
@Import({RoomAccessService.class, RoomMemberService.class})
class RoomMemberServiceDirectRoomTest {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomMemberService roomMemberService;

    @Test
    void listMembersOfADirectRoomResolvesBothParticipants() {
        UUID roomId = UUID.randomUUID();
        roomRepository.save(new Room(roomId, null, RoomType.DIRECT, "subA", Instant.now(), "subA:subB"));
        roomMemberRepository.save(new RoomMember(roomId, "subA", RoomRole.MEMBER, Instant.now()));
        roomMemberRepository.save(new RoomMember(roomId, "subB", RoomRole.MEMBER, Instant.now()));

        List<RoomMember> members = roomMemberService.listMembers("subA", roomId);

        assertThat(members).hasSize(2);
        assertThat(members).extracting(RoomMember::getUserId).containsExactlyInAnyOrder("subA", "subB");
        assertThat(members).allMatch(member -> member.getRole() == RoomRole.MEMBER);
    }
}
