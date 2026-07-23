package com.chattera.profile.web;

import java.time.Instant;

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

import com.chattera.profile.config.SecurityConfig;
import com.chattera.profile.domain.Profile;
import com.chattera.profile.presence.PresenceReader;
import com.chattera.profile.presence.PresenceStatus;
import com.chattera.profile.service.ProfileService;
import com.chattera.security.JwtAuthenticationConverterAutoConfiguration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies GET/PUT /me wiring: authentication is required, the response
 * shape combines the persisted profile with a live presence read, and
 * unauthenticated requests are rejected by the real SecurityConfig (imported
 * here, not reimplemented). ProfileService/PresenceReader are mocked - their
 * own behavior is covered by ProfileServiceTest.
 */
@WebMvcTest(controllers = ProfileController.class)
@Import({SecurityConfig.class, JwtAuthenticationConverterAutoConfiguration.class, ProfileControllerTest.NoOpJwtDecoderConfig.class})
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProfileService profileService;

    @MockBean
    private PresenceReader presenceReader;

    @TestConfiguration
    static class NoOpJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            // Never actually invoked: SecurityMockMvcRequestPostProcessors.jwt()
            // injects a pre-built Authentication directly. A JwtDecoder bean
            // just needs to exist for the resource-server filter chain to build.
            return token -> {
                throw new UnsupportedOperationException("not used in tests");
            };
        }
    }

    @Test
    void getMeIsRejectedWithoutAToken() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMeReturnsProfileAndPresenceStatus() throws Exception {
        Profile profile = new Profile("user-1", "Ada", null, "UTC", Instant.parse("2026-01-01T00:00:00Z"));
        when(profileService.getOrProvision(any())).thenReturn(profile);
        when(presenceReader.readStatus(eq("user-1"))).thenReturn(PresenceStatus.ONLINE);

        mockMvc.perform(get("/me").with(jwt().jwt(builder -> builder.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.displayName").value("Ada"))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.status").value("online"));
    }

    @Test
    void putMeUpdatesProfileAndReturnsCurrentPresence() throws Exception {
        Profile updated = new Profile("user-2", "New Name", null, null, Instant.parse("2026-01-01T00:00:00Z"));
        when(profileService.updateProfile(any(), any())).thenReturn(updated);
        when(presenceReader.readStatus(eq("user-2"))).thenReturn(PresenceStatus.OFFLINE);

        mockMvc.perform(put("/me")
                        .with(jwt().jwt(builder -> builder.subject("user-2")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"))
                .andExpect(jsonPath("$.status").value("offline"));
    }

    @Test
    void putMeRejectsAnOverlongDisplayName() throws Exception {
        String tooLong = "x".repeat(256);

        mockMvc.perform(put("/me")
                        .with(jwt().jwt(builder -> builder.subject("user-3")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
