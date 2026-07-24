package com.chattera.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.chattera.domain.event.DomainEvent;

/**
 * Auto-configures the shared RabbitMQ event bus for any Chattera service
 * that has spring-boot-starter-amqp on the classpath (pulled in transitively
 * by depending on this module) and standard {@code spring.rabbitmq.*}
 * connection properties set. Mirrors the common-security pattern: a service
 * gets a working {@code EventPublisher<DomainEvent>} bean for free just by
 * adding the dependency, and can still fully override any bean here
 * ({@code @ConditionalOnMissingBean}).
 *
 * <p>Runs after {@link RabbitAutoConfiguration} so it can see the
 * {@link RabbitTemplate} Boot already builds from {@code spring.rabbitmq.*}
 * (host/port/credentials); this configuration only adds the JSON message
 * converter, declares the shared topic exchange, and wraps the template in
 * the {@link EventPublisher} abstraction.
 */
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnClass(RabbitTemplate.class)
@EnableConfigurationProperties(ChatteraMessagingProperties.class)
public class RabbitEventPublisherAutoConfiguration {

    /**
     * Declared as a bean so Spring AMQP's {@code RabbitAdmin} auto-declares
     * it on the broker at startup - producers never need to declare it
     * themselves.
     */
    @Bean
    @ConditionalOnMissingBean
    public TopicExchange chatteraEventsExchange(ChatteraMessagingProperties properties) {
        return new TopicExchange(properties.getExchange(), true, false);
    }

    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter chatteraEventMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher<DomainEvent> eventPublisher(
            RabbitTemplate rabbitTemplate, ChatteraMessagingProperties properties, MessageConverter messageConverter) {
        rabbitTemplate.setMessageConverter(messageConverter);
        return new RabbitEventPublisher(rabbitTemplate, properties.getExchange());
    }
}
