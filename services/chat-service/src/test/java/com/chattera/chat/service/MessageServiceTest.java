package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import com.chattera.chat.domain.Message;
import com.chattera.chat.domain.MessageStatus;
import com.chattera.chat.repository.MessageRepository;
import com.chattera.chat.service.exception.InvalidCursorException;
import com.chattera.chat.service.exception.NotRoomMemberException;
import com.chattera.chat.web.dto.PostMessageRequest;
import com.chattera.domain.event.RoomMessageCreatedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private RoomAccessService roomAccessService;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(roomAccessService, messageRepository, applicationEventPublisher);
    }

    @Test
    void postMessagePersistsWithSentStatusAndPublishesADomainEvent() {
        UUID roomId = UUID.randomUUID();
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message message = messageService.postMessage("user-1", roomId, new PostMessageRequest("hello"));

        assertThat(message.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message.getSenderId()).isEqualTo("user-1");
        assertThat(message.getRoomId()).isEqualTo(roomId);
        assertThat(message.getContent()).isEqualTo("hello");
        verify(roomAccessService).requireMembership(roomId, "user-1");

        ArgumentCaptor<RoomMessageCreatedEvent> eventCaptor = ArgumentCaptor.forClass(RoomMessageCreatedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        RoomMessageCreatedEvent event = eventCaptor.getValue();
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.messageId()).isEqualTo(message.getId());
        assertThat(event.content()).isEqualTo("hello");
    }

    @Test
    void postMessageRejectsNonMembersBeforeTouchingTheRepository() {
        UUID roomId = UUID.randomUUID();
        doThrow(new NotRoomMemberException(roomId)).when(roomAccessService).requireMembership(roomId, "outsider");

        assertThatThrownBy(() -> messageService.postMessage("outsider", roomId, new PostMessageRequest("hi")))
                .isInstanceOf(NotRoomMemberException.class);
        verify(messageRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void getHistoryDefaultsToFirstPageWhenNoCursorGiven() {
        UUID roomId = UUID.randomUUID();
        List<Message> onePage = List.of(
                new Message(UUID.randomUUID(), roomId, "s", "m1", MessageStatus.SENT, Instant.now()));
        when(messageRepository.findByRoomIdOrderByCreatedAtDescIdDesc(eq(roomId), any(Pageable.class))).thenReturn(onePage);

        MessagePage page = messageService.getHistory("user-1", roomId, null, null);

        assertThat(page.messages()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void getHistoryReturnsANextCursorWhenMoreHistoryExists() {
        UUID roomId = UUID.randomUUID();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // MessageService.MAX_LIMIT is 50; request a small limit (2) and return
        // 3 rows (limit + 1) to simulate "more history available".
        List<Message> threeRows = List.of(
                new Message(UUID.randomUUID(), roomId, "s", "m3", MessageStatus.SENT, base.plusSeconds(3)),
                new Message(UUID.randomUUID(), roomId, "s", "m2", MessageStatus.SENT, base.plusSeconds(2)),
                new Message(UUID.randomUUID(), roomId, "s", "m1", MessageStatus.SENT, base.plusSeconds(1)));
        when(messageRepository.findByRoomIdOrderByCreatedAtDescIdDesc(eq(roomId), any(Pageable.class))).thenReturn(threeRows);

        MessagePage page = messageService.getHistory("user-1", roomId, 2, null);

        assertThat(page.messages()).hasSize(2);
        assertThat(page.messages()).extracting(Message::getContent).containsExactly("m3", "m2");
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void getHistoryClampsAnOverlargeLimitToTheHardMax() {
        UUID roomId = UUID.randomUUID();
        when(messageRepository.findByRoomIdOrderByCreatedAtDescIdDesc(eq(roomId), any(Pageable.class))).thenReturn(List.of());

        messageService.getHistory("user-1", roomId, 10_000, null);

        verify(messageRepository).findByRoomIdOrderByCreatedAtDescIdDesc(eq(roomId), any(Pageable.class));
    }

    @Test
    void getHistoryWithAMalformedCursorThrowsInvalidCursor() {
        UUID roomId = UUID.randomUUID();

        assertThatThrownBy(() -> messageService.getHistory("user-1", roomId, 10, "not-a-valid-cursor!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void getHistoryUsesTheBeforeCursorQueryWhenACursorIsGiven() {
        UUID roomId = UUID.randomUUID();
        UUID cursorMessageId = UUID.randomUUID();
        Instant cursorCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((cursorCreatedAt + "|" + cursorMessageId).getBytes());
        when(messageRepository.findByRoomIdBeforeCursor(eq(roomId), eq(cursorCreatedAt), eq(cursorMessageId), any(Pageable.class)))
                .thenReturn(List.of());

        messageService.getHistory("user-1", roomId, 10, cursor);

        verify(messageRepository).findByRoomIdBeforeCursor(eq(roomId), eq(cursorCreatedAt), eq(cursorMessageId), any(Pageable.class));
        verify(messageRepository, never()).findByRoomIdOrderByCreatedAtDescIdDesc(any(), any());
    }
}
