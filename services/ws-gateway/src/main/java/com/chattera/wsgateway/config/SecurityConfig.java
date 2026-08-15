package com.chattera.wsgateway.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * ws-gateway's HTTP-layer security is deliberately permissive: the WebSocket
 * handshake (GET/POST {@code /ws}) is a plain, unauthenticated HTTP exchange
 * that only upgrades the connection - the actual authentication happens one
 * layer up, at the STOMP {@code CONNECT} frame ({@code StompAuthChannelInterceptor}),
 * per solution-architecture.md "On WebSocket, the token is passed on the
 * STOMP CONNECT frame ... and validated there." common-security's
 * {@code JwtDecoder} bean (issuer-uri + azp allowlist) is still auto-configured
 * here and injected directly into that interceptor - this filter chain just
 * doesn't gate HTTP requests with it, since there are none to protect beyond
 * the handshake and the health probe.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Allows the browser-based dev console (dev-tools/web-console, served on
     * http://localhost:3000) to open the WebSocket handshake - same origin
     * allowance as profile-service/chat-service's CORS config.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
