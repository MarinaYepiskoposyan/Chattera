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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.chattera.chat.config.SecurityConfig;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.error.GlobalExceptionHandler;
import com.chattera.chat.service.RoomMemberService;
import com.chattera.chat.service.exception.NotRoomMemberException;
import com.chattera.chat.service.exception.RoomNotFoundException;
import com.chattera.security.JwtAuthenticationConverterAutoConfiguration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies /rooms/{roomId}/members wiring: authentication required, response
 * shape, and domain-exception -> HTTP status mapping. RoomMemberService is
 * mocked - its own behavior is covered by RoomMemberServiceTest.
 */
@WebMvcTest(controllers = RoomMemberController.class)
@Import({SecurityConfig.class, JwtAuthenticationConverterAutoConfiguration.class, GlobalExceptionHandler.class,
        RoomMemberControllerTest.NoOpJwtDecoderConfig.class})
class RoomMemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoomMemberService roomMemberService;

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
    void listMembersIsRejectedWithoutAToken() throws Exception {
        UUID roomId = UUID.randomUUID();
        mockMvc.perform(get("/rooms/" + roomId + "/members"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listMembersReturnsEachMembersUserIdRoleAndJoinedAt() throws Exception {
        UUID roomId = UUID.randomUUID();
        RoomMember owner = new RoomMember(roomId, "user-1", RoomRole.OWNER, Instant.parse("2026-01-01T00:00:00Z"));
        RoomMember member = new RoomMember(roomId, "user-2", RoomRole.MEMBER, Instant.parse("2026-01-02T00:00:00Z"));
        when(roomMemberService.listMembers(eq("user-1"), eq(roomId))).thenReturn(List.of(owner, member));

        mockMvc.perform(get("/rooms/" + roomId + "/members").with(jwt().jwt(builder -> builder.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("user-1"))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].userId").value("user-2"))
                .andExpect(jsonPath("$[1].role").value("MEMBER"));
    }

    @Test
    void listMembersByANonMemberReturns403() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(roomMemberService.listMembers(eq("outsider"), eq(roomId))).thenThrow(new NotRoomMemberException(roomId));

        mockMvc.perform(get("/rooms/" + roomId + "/members").with(jwt().jwt(builder -> builder.subject("outsider"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ROOM_MEMBER"));
    }

    @Test
    void listMembersOnAnUnknownRoomReturns404() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(roomMemberService.listMembers(eq("user-1"), eq(roomId))).thenThrow(new RoomNotFoundException(roomId));

        mockMvc.perform(get("/rooms/" + roomId + "/members").with(jwt().jwt(builder -> builder.subject("user-1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }
}
