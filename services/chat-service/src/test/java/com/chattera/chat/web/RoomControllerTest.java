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
import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.error.GlobalExceptionHandler;
import com.chattera.chat.service.DirectRoomOutcome;
import com.chattera.chat.service.RoomService;
import com.chattera.chat.service.RoomWithMembership;
import com.chattera.chat.service.exception.RoomNotSelfJoinableException;
import com.chattera.chat.service.exception.SelfDmNotAllowedException;
import com.chattera.security.JwtAuthenticationConverterAutoConfiguration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies /rooms wiring: authentication required, request validation,
 * response shape, and domain-exception -> HTTP status mapping. RoomService
 * is mocked - its own behavior is covered by RoomServiceTest.
 */
@WebMvcTest(controllers = RoomController.class)
@Import({SecurityConfig.class, JwtAuthenticationConverterAutoConfiguration.class, GlobalExceptionHandler.class,
        RoomControllerTest.NoOpJwtDecoderConfig.class})
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoomService roomService;

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
    void listRoomsIsRejectedWithoutAToken() throws Exception {
        mockMvc.perform(get("/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRoomReturnsTheCreatedRoomWithOwnerRole() throws Exception {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "user-1", Instant.parse("2026-01-01T00:00:00Z"));
        when(roomService.createRoom(eq("user-1"), any())).thenReturn(new RoomWithMembership(room, RoomRole.OWNER));

        mockMvc.perform(post("/rooms")
                        .with(jwt().jwt(builder -> builder.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"general\",\"type\":\"PUBLIC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("general"))
                .andExpect(jsonPath("$.type").value("PUBLIC"))
                .andExpect(jsonPath("$.member").value(true))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void createRoomRejectsABlankName() throws Exception {
        mockMvc.perform(post("/rooms")
                        .with(jwt().jwt(builder -> builder.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"type\":\"PUBLIC\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listRoomsReturnsWhatTheServiceReports() throws Exception {
        Room room = new Room(UUID.randomUUID(), "general", RoomType.PUBLIC, "someone-else", Instant.parse("2026-01-01T00:00:00Z"));
        when(roomService.listVisibleRooms("user-1")).thenReturn(List.of(new RoomWithMembership(room, null)));

        mockMvc.perform(get("/rooms").with(jwt().jwt(builder -> builder.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("general"))
                .andExpect(jsonPath("$[0].member").value(false))
                .andExpect(jsonPath("$[0].role").doesNotExist());
    }

    @Test
    void joinRoomReturnsTheUpdatedMembership() throws Exception {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, "general", RoomType.PUBLIC, "someone-else", Instant.parse("2026-01-01T00:00:00Z"));
        when(roomService.joinRoom("user-2", roomId)).thenReturn(new RoomWithMembership(room, RoomRole.MEMBER));

        mockMvc.perform(post("/rooms/" + roomId + "/join").with(jwt().jwt(builder -> builder.subject("user-2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(true))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    void joinRoomOnAPrivateRoomReturns403() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(roomService.joinRoom("user-2", roomId)).thenThrow(new RoomNotSelfJoinableException(roomId));

        mockMvc.perform(post("/rooms/" + roomId + "/join").with(jwt().jwt(builder -> builder.subject("user-2"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_SELF_JOINABLE"));
    }

    @Test
    void createDirectRoomReturns201WhenANewRoomWasCreated() throws Exception {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, null, RoomType.DIRECT, "user-1", Instant.parse("2026-01-01T00:00:00Z"));
        when(roomService.findOrCreateDirect("user-1", "user-2"))
                .thenReturn(new DirectRoomOutcome(new RoomWithMembership(room, RoomRole.MEMBER), true));

        mockMvc.perform(post("/rooms/direct")
                        .with(jwt().jwt(builder -> builder.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DIRECT"))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.member").value(true))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    void createDirectRoomReturns200WhenAnExistingRoomWasFound() throws Exception {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, null, RoomType.DIRECT, "user-1", Instant.parse("2026-01-01T00:00:00Z"));
        when(roomService.findOrCreateDirect("user-2", "user-1"))
                .thenReturn(new DirectRoomOutcome(new RoomWithMembership(room, RoomRole.MEMBER), false));

        mockMvc.perform(post("/rooms/direct")
                        .with(jwt().jwt(builder -> builder.subject("user-2")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(roomId.toString()));
    }

    @Test
    void createDirectRoomRejectsSelfDm() throws Exception {
        when(roomService.findOrCreateDirect("user-1", "user-1")).thenThrow(new SelfDmNotAllowedException());

        mockMvc.perform(post("/rooms/direct")
                        .with(jwt().jwt(builder -> builder.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_DM_NOT_ALLOWED"));
    }

    @Test
    void createDirectRoomRejectsABlankUserId() throws Exception {
        mockMvc.perform(post("/rooms/direct")
                        .with(jwt().jwt(builder -> builder.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createDirectRoomIsRejectedWithoutAToken() throws Exception {
        mockMvc.perform(post("/rooms/direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-2\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void leaveRoomReturnsNoContent() throws Exception {
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(post("/rooms/" + roomId + "/leave").with(jwt().jwt(builder -> builder.subject("user-2"))))
                .andExpect(status().isNoContent());
    }
}
