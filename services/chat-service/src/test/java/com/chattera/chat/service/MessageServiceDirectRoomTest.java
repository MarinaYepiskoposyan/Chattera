package com.chattera.chat.service;

import java.time.Instant;
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
import com.chattera.chat.service.exception.NotRoomMemberException;
import com.chattera.chat.testsupport.ChatDataJpaTest;
import com.chattera.chat.web.dto.PostMessageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CHAT-105 AC-10/AC-11/AC-12/AC-13: MessageService's post/history paths
 * never branch on RoomType (confirmed by inspection), but no prior test
 * actually drove a real RoomType.DIRECT room through them - MessageServiceTest
 * mocks RoomAccessService wholesale, so a Room's type never reaches the code
 * under test there. This uses a real (embedded H2) database, same
 * {@code @ChatDataJpaTest} + {@code @Import} pattern as
 * RoomServiceConcurrentDirectTest, so RoomAccessService's actual
 * room-lookup/membership-check logic runs against a genuine DIRECT room row.
 */
@ChatDataJpaTest
@Import({RoomAccessService.class, MessageService.class})
class MessageServiceDirectRoomTest {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private MessageService messageService;

    private UUID createDirectRoom(String subA, String subB) {
        UUID roomId = UUID.randomUUID();
        String directKey = subA.compareTo(subB) <= 0 ? subA + ":" + subB : subB + ":" + subA;
        roomRepository.save(new Room(roomId, null, RoomType.DIRECT, subA, Instant.now(), directKey));
        roomMemberRepository.save(new RoomMember(roomId, subA, RoomRole.MEMBER, Instant.now()));
        roomMemberRepository.save(new RoomMember(roomId, subB, RoomRole.MEMBER, Instant.now()));
        return roomId;
    }

    @Test
    void aDirectRoomParticipantCanPostAMessageAndTheOtherParticipantCanReadItBack() {
        UUID roomId = createDirectRoom("subA", "subB");

        messageService.postMessage("subA", roomId, new PostMessageRequest("hello subB"));
        MessagePage page = messageService.getHistory("subB", roomId, null, null);

        assertThat(page.messages()).hasSize(1);
        assertThat(page.messages().get(0).getContent()).isEqualTo("hello subB");
        assertThat(page.messages().get(0).getSenderId()).isEqualTo("subA");
    }

    @Test
    void aNonParticipantCannotPostToOrReadHistoryOfADirectRoom() {
        UUID roomId = createDirectRoom("subA", "subB");

        assertThatThrownBy(() -> messageService.postMessage("outsider", roomId, new PostMessageRequest("hi")))
                .isInstanceOf(NotRoomMemberException.class);
        assertThatThrownBy(() -> messageService.getHistory("outsider", roomId, null, null))
                .isInstanceOf(NotRoomMemberException.class);
    }
}
