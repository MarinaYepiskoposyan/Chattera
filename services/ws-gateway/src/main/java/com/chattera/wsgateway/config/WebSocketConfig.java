package com.chattera.wsgateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.chattera.wsgateway.auth.StompAuthChannelInterceptor;

/**
 * STOMP-over-WebSocket wiring (CHAT-107). Uses Spring's <b>in-memory simple
 * broker</b> ({@code enableSimpleBroker("/topic")}) - deliberately
 * <b>not</b> {@code enableStompBrokerRelay}, per solution-architecture.md's
 * CHAT-24 §3 correction ("broadcast queue per pod + app-side room->session
 * filtering", not "relay per subscription"). The simple broker's own
 * per-pod subscription registry is what does the actual "only push to
 * sockets that are subscribed" filtering - {@code RoomEventBroadcastListener}
 * hands events to it via {@code SimpMessagingTemplate} rather than
 * hand-rolling a roomId-&gt;session map.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins("http://localhost:3000");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
