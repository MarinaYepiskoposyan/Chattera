package com.chattera.messaging;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import com.chattera.domain.event.DomainEvent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The one contractual guarantee every {@link EventPublisher} implementation
 * must uphold (see that interface's Javadoc): {@code publish} never throws,
 * even when the underlying transport fails.
 */
class KafkaEventPublisherTest {

    private record TestEvent(Instant occurredAt) implements DomainEvent {
    }

    @Test
    void publishSendsToTheConfiguredTopicNamespace() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        ChatteraMessagingProperties properties = new ChatteraMessagingProperties();
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate, properties);
        TestEvent event = new TestEvent(Instant.now());

        publisher.publish("room.123", event);

        verify(kafkaTemplate).send("chattera.events.room.123", event);
    }

    @Test
    void publishSwallowsAKafkaExceptionRatherThanPropagatingIt() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        doThrow(new KafkaException("broker unreachable"))
                .when(kafkaTemplate).send(anyString(), any(Object.class));
        ChatteraMessagingProperties properties = new ChatteraMessagingProperties();
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate, properties);

        assertThatCode(() -> publisher.publish("room.123", new TestEvent(Instant.now())))
                .doesNotThrowAnyException();
    }
}
