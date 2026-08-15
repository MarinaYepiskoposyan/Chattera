package com.chattera.chat.service;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.chattera.chat.repository.MessageRepository;
import com.chattera.domain.event.MessageDeliveredEvent;
import com.chattera.domain.event.MessageReadEvent;
import com.chattera.domain.event.RoomMessageStatusChangedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageStatusServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private MessageStatusService messageStatusService;

    @BeforeEach
    void setUp() {
        messageStatusService = new MessageStatusService(messageRepository, applicationEventPublisher);
    }

    @Test
    void applyDeliveredPublishesAStatusChangedEventWhenARowIsAdvanced() {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        when(messageRepository.markDelivered(messageId)).thenReturn(1);

        messageStatusService.applyDelivered(new MessageDeliveredEvent(messageId, roomId, "recipient", Instant.now(), Instant.now()));

        ArgumentCaptor<RoomMessageStatusChangedEvent> captor = ArgumentCaptor.forClass(RoomMessageStatusChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        RoomMessageStatusChangedEvent event = captor.getValue();
        assertThat(event.messageId()).isEqualTo(messageId);
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.status()).isEqualTo("DELIVERED");
        assertThat(event.recipientId()).isEqualTo("recipient");
    }

    @Test
    void applyDeliveredIsANoOpWhenNoRowAdvances() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.markDelivered(messageId)).thenReturn(0);

        messageStatusService.applyDelivered(
                new MessageDeliveredEvent(messageId, UUID.randomUUID(), "recipient", Instant.now(), Instant.now()));

        verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void applyReadPublishesAStatusChangedEventWhenRowsAreAdvanced() {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        when(messageRepository.markReadUpTo(roomId, "recipient", messageId)).thenReturn(3);

        messageStatusService.applyRead(new MessageReadEvent(messageId, roomId, "recipient", Instant.now(), Instant.now()));

        ArgumentCaptor<RoomMessageStatusChangedEvent> captor = ArgumentCaptor.forClass(RoomMessageStatusChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("READ");
        assertThat(captor.getValue().messageId()).isEqualTo(messageId);
    }

    @Test
    void applyReadIsANoOpWhenNoRowAdvances() {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        when(messageRepository.markReadUpTo(roomId, "recipient", messageId)).thenReturn(0);

        messageStatusService.applyRead(new MessageReadEvent(messageId, roomId, "recipient", Instant.now(), Instant.now()));

        verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
