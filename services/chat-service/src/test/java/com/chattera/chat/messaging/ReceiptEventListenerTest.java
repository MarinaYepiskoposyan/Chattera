package com.chattera.chat.messaging;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.chattera.chat.service.MessageStatusService;
import com.chattera.domain.event.MessageDeliveredEvent;
import com.chattera.domain.event.MessageReadEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReceiptEventListenerTest {

    @Test
    void routesADeliveredEventToMessageStatusService() {
        MessageStatusService messageStatusService = mock(MessageStatusService.class);
        ReceiptEventListener listener = new ReceiptEventListener(messageStatusService);
        MessageDeliveredEvent event = new MessageDeliveredEvent(
                UUID.randomUUID(), UUID.randomUUID(), "recipient", Instant.now(), Instant.now());

        listener.onDelivered(event);

        verify(messageStatusService).applyDelivered(event);
    }

    @Test
    void routesAReadEventToMessageStatusService() {
        MessageStatusService messageStatusService = mock(MessageStatusService.class);
        ReceiptEventListener listener = new ReceiptEventListener(messageStatusService);
        MessageReadEvent event = new MessageReadEvent(
                UUID.randomUUID(), UUID.randomUUID(), "recipient", Instant.now(), Instant.now());

        listener.onRead(event);

        verify(messageStatusService).applyRead(event);
    }
}
