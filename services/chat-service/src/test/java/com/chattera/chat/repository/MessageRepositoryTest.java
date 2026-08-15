package com.chattera.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.chattera.chat.domain.Message;
import com.chattera.chat.domain.MessageStatus;
import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.testsupport.ChatDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@ChatDataJpaTest
class MessageRepositoryTest {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void findByRoomIdOrderByCreatedAtDescIdDescReturnsNewestFirstBoundedByPageSize() {
        UUID roomId = roomRepository.save(new Room(UUID.randomUUID(), "room", RoomType.PUBLIC, "owner", Instant.now())).getId();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            messageRepository.save(new Message(UUID.randomUUID(), roomId, "sender", "msg-" + i, MessageStatus.SENT,
                    base.plusSeconds(i)));
        }

        List<Message> page = messageRepository.findByRoomIdOrderByCreatedAtDescIdDesc(roomId, PageRequest.of(0, 3));

        assertThat(page).hasSize(3);
        assertThat(page).extracting(Message::getContent).containsExactly("msg-4", "msg-3", "msg-2");
    }

    @Test
    void findByRoomIdBeforeCursorContinuesFromTheGivenCreatedAtAndId() {
        UUID roomId = roomRepository.save(new Room(UUID.randomUUID(), "room", RoomType.PUBLIC, "owner", Instant.now())).getId();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            messageRepository.save(new Message(UUID.randomUUID(), roomId, "sender", "msg-" + i, MessageStatus.SENT,
                    base.plusSeconds(i)));
        }
        // Cursor is (msg-2's createdAt, msg-2's id) - "before" that should yield msg-1, msg-0.
        Message msg2 = messageRepository.findByRoomIdOrderByCreatedAtDescIdDesc(roomId, PageRequest.of(0, 5)).stream()
                .filter(m -> m.getContent().equals("msg-2"))
                .findFirst().orElseThrow();

        List<Message> olderPage = messageRepository.findByRoomIdBeforeCursor(
                roomId, msg2.getCreatedAt(), msg2.getId(), PageRequest.of(0, 10));

        assertThat(olderPage).extracting(Message::getContent).containsExactly("msg-1", "msg-0");
    }

    @Test
    void messagesInDifferentRoomsDoNotLeakIntoEachOthersHistory() {
        UUID roomA = roomRepository.save(new Room(UUID.randomUUID(), "room-a", RoomType.PUBLIC, "owner", Instant.now())).getId();
        UUID roomB = roomRepository.save(new Room(UUID.randomUUID(), "room-b", RoomType.PUBLIC, "owner", Instant.now())).getId();
        messageRepository.save(new Message(UUID.randomUUID(), roomA, "sender", "in-a", MessageStatus.SENT, Instant.now()));
        messageRepository.save(new Message(UUID.randomUUID(), roomB, "sender", "in-b", MessageStatus.SENT, Instant.now()));

        List<Message> roomAHistory = messageRepository.findByRoomIdOrderByCreatedAtDescIdDesc(roomA, PageRequest.of(0, 10));

        assertThat(roomAHistory).extracting(Message::getContent).containsExactly("in-a");
    }

    @Test
    void markReadUpToIgnoresALastReadMessageIdThatBelongsToADifferentRoom() {
        // CHAT-38 regression: a lastReadMessageId from room B must not be
        // usable to resolve a read cutoff timestamp applied to room A.
        UUID roomA = roomRepository.save(new Room(UUID.randomUUID(), "room-a", RoomType.PUBLIC, "owner", Instant.now())).getId();
        UUID roomB = roomRepository.save(new Room(UUID.randomUUID(), "room-b", RoomType.PUBLIC, "owner", Instant.now())).getId();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");

        Message messageInRoomA = messageRepository.save(
                new Message(UUID.randomUUID(), roomA, "other-user", "in-a", MessageStatus.SENT, base));
        // A far-future message in room B: if the room check were missing, its
        // late createdAt would resolve as a cutoff that covers messageInRoomA.
        Message messageInRoomB = messageRepository.save(
                new Message(UUID.randomUUID(), roomB, "other-user", "in-b", MessageStatus.SENT, base.plusSeconds(3600)));

        int updated = messageRepository.markReadUpTo(roomA, "recipient", messageInRoomB.getId());

        assertThat(updated).isZero();
        Message reloaded = messageRepository.findById(messageInRoomA.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MessageStatus.SENT);
    }
}
