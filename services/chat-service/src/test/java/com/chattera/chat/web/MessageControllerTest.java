package com.chattera.chat.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.chattera.chat.config.SecurityConfig;
import com.chattera.chat.domain.Message;
import com.chattera.chat.domain.MessageStatus;
import com.chattera.chat.error.GlobalExceptionHandler;
import com.chattera.chat.service.MessagePage;
import com.chattera.chat.service.MessageService;
import com.chattera.chat.service.exception.NotRoomMemberException;
import com.chattera.security.JwtAuthenticationConverterAutoConfiguration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies /rooms/{roomId}/messages wiring: authentication required, request
 * validation, response shape, and domain-exception -> HTTP status mapping.
 * MessageService is mocked - its own behavior is covered by MessageServiceTest.
 */
@WebMvcTest(controllers = MessageController.class)
@Import({SecurityConfig.class, JwtAuthenticationConverterAutoConfiguration.class, GlobalExceptionHandler.class,
        MessageControllerTest.NoOpJwtDecoderConfig.class})
class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageService messageService;

    @TestConfiguration
    static class NoOpJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("not used in tests");
            };
        }
    }

    @Test
    void postMessageIsRejectedWithoutAToken() throws Exception {
        UUID roomId = UUID.randomUUID();
        mockMvc.perform(post("/rooms/" + roomId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postMessageReturnsTheCreatedMessage() throws Exception {
        UUID roomId = UUID.randomUUID();
        Message message = new Message(UUID.randomUUID(), roomId, "user-1", "hello", MessageStatus.SENT,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(messageService.postMessage(eq("user-1"), eq(roomId), any())).thenReturn(message);

        mockMvc.perform(post("/rooms/" + roomId + "/messages")
                        .with(jwt().jwt(builder -> builder.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hello"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.senderId").value("user-1"));
    }

    @Test
    void postMessageRejectsBlankContent() throws Exception {
        UUID roomId = UUID.randomUUID();
        mockMvc.perform(post("/rooms/" + roomId + "/messages")
                        .with(jwt().jwt(builder -> builder.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postMessageByANonMemberReturns403() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(messageService.postMessage(eq("outsider"), eq(roomId), any()))
                .thenThrow(new NotRoomMemberException(roomId));

        mockMvc.perform(post("/rooms/" + roomId + "/messages")
                        .with(jwt().jwt(builder -> builder.subject("outsider")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ROOM_MEMBER"));
    }

    @Test
    void getHistoryReturnsMessagesAndNextCursor() throws Exception {
        UUID roomId = UUID.randomUUID();
        Message message = new Message(UUID.randomUUID(), roomId, "user-1", "hello", MessageStatus.SENT,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(messageService.getHistory(eq("user-1"), eq(roomId), any(), isNull()))
                .thenReturn(new MessagePage(List.of(message), "opaque-cursor"));

        mockMvc.perform(get("/rooms/" + roomId + "/messages").with(jwt().jwt(builder -> builder.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("hello"))
                .andExpect(jsonPath("$.nextCursor").value("opaque-cursor"));
    }

    @Test
    void getHistoryPassesLimitAndBeforeThroughToTheService() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(messageService.getHistory(eq("user-1"), eq(roomId), eq(10), eq("some-cursor")))
                .thenReturn(new MessagePage(List.of(), null));

        mockMvc.perform(get("/rooms/" + roomId + "/messages?limit=10&before=some-cursor")
                        .with(jwt().jwt(builder -> builder.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
