package com.chattera.messaging;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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
class RabbitEventPublisherTest {

    private record TestEvent(Instant occurredAt) implements DomainEvent {
    }

    @Test
    void publishSendsToTheConfiguredExchangeWithTheGivenRoutingKey() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitEventPublisher publisher = new RabbitEventPublisher(rabbitTemplate, "chattera.events");
        TestEvent event = new TestEvent(Instant.now());

        publisher.publish("room.123", event);

        verify(rabbitTemplate).convertAndSend("chattera.events", "room.123", event);
    }

    @Test
    void publishSwallowsAnAmqpExceptionRatherThanPropagatingIt() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new AmqpException("broker unreachable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
        RabbitEventPublisher publisher = new RabbitEventPublisher(rabbitTemplate, "chattera.events");

        assertThatCode(() -> publisher.publish("room.123", new TestEvent(Instant.now())))
                .doesNotThrowAnyException();
    }
}
