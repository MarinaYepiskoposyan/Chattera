package com.chattera.profile.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;

import com.chattera.profile.domain.Profile;
import com.chattera.profile.repository.ProfileRepository;
import com.chattera.profile.web.dto.UpdateProfileRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private ProfileRepository profileRepository;

    /**
     * Stands in for ProfileService's self-proxy reference (see the class
     * Javadoc on {@code ProfileService.self}): provisioning goes through
     * this rather than {@code profileRepository.save(...)} directly, so
     * that's what these tests stub/verify for the provisioning path.
     */
    @Mock
    private ProfileService self;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(profileRepository, self);
    }

    @Test
    void provisionsANewProfileFromPreferredUsernameOnFirstAuthenticatedRequest() {
        Jwt jwt = jwtFor("user-1", "ada.lovelace", null);
        Profile provisioned = new Profile("user-1", "ada.lovelace", null, null, Instant.now());
        when(profileRepository.findById("user-1")).thenReturn(Optional.empty());
        when(self.insertNewProfile("user-1", "ada.lovelace")).thenReturn(provisioned);

        Profile profile = profileService.getOrProvision(jwt);

        assertThat(profile).isSameAs(provisioned);
        assertThat(profile.getUserId()).isEqualTo("user-1");
        assertThat(profile.getDisplayName()).isEqualTo("ada.lovelace");
        assertThat(profile.getCreatedAt()).isNotNull();
        verify(self).insertNewProfile("user-1", "ada.lovelace");
    }

    @Test
    void fallsBackToNameClaimWhenPreferredUsernameIsAbsent() {
        Jwt jwt = jwtFor("user-2", null, "Ada Lovelace");
        Profile provisioned = new Profile("user-2", "Ada Lovelace", null, null, Instant.now());
        when(profileRepository.findById("user-2")).thenReturn(Optional.empty());
        when(self.insertNewProfile("user-2", "Ada Lovelace")).thenReturn(provisioned);

        Profile profile = profileService.getOrProvision(jwt);

        assertThat(profile.getDisplayName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void defaultsToNullDisplayNameWhenNeitherClaimIsPresent() {
        Jwt jwt = jwtFor("user-3", null, null);
        Profile provisioned = new Profile("user-3", null, null, null, Instant.now());
        when(profileRepository.findById("user-3")).thenReturn(Optional.empty());
        when(self.insertNewProfile("user-3", null)).thenReturn(provisioned);

        Profile profile = profileService.getOrProvision(jwt);

        assertThat(profile.getDisplayName()).isNull();
    }

    @Test
    void doesNotReprovisionAnExistingProfile() {
        Profile existing = new Profile("user-4", "Existing Name", null, null, Instant.now());
        when(profileRepository.findById("user-4")).thenReturn(Optional.of(existing));
        Jwt jwt = jwtFor("user-4", "ignored-username", null);

        Profile profile = profileService.getOrProvision(jwt);

        assertThat(profile).isSameAs(existing);
        verify(self, never()).insertNewProfile(any(), any());
    }

    @Test
    void losingTheProvisioningRaceReadsBackTheWinnersRowInsteadOfThrowing() {
        // Simulates the interleaving from the CHAT-103 review finding: this call's
        // own findById misses (the other request hasn't committed yet), its own
        // insertNewProfile attempt then hits the other request's already-committed
        // row and throws a duplicate-key violation, and the fix is that we catch
        // that and read back the winner's row instead of letting the exception
        // surface as a bare 500.
        Jwt jwt = jwtFor("user-7", "loser-of-the-race", null);
        Profile winnersRow = new Profile("user-7", "winner-of-the-race", null, null, Instant.now());
        when(profileRepository.findById("user-7"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(self.insertNewProfile("user-7", "loser-of-the-race"))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        Profile profile = profileService.getOrProvision(jwt);

        assertThat(profile).isSameAs(winnersRow);
        assertThat(profile.getDisplayName()).isEqualTo("winner-of-the-race");
    }

    @Test
    void updateProfileProvisionsFirstThenAppliesOnlyProvidedFields() {
        Profile provisioned = new Profile("user-5", "new-user", null, null, Instant.now());
        when(profileRepository.findById("user-5")).thenReturn(Optional.empty());
        when(self.insertNewProfile("user-5", "new-user")).thenReturn(provisioned);
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Jwt jwt = jwtFor("user-5", "new-user", null);

        UpdateProfileRequest request = new UpdateProfileRequest(null, "https://example.com/avatar.png", null);
        Profile profile = profileService.updateProfile(jwt, request);

        assertThat(profile.getUserId()).isEqualTo("user-5");
        assertThat(profile.getDisplayName()).isEqualTo("new-user");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void updateProfileLeavesUnspecifiedFieldsUnchanged() {
        Profile existing = new Profile("user-6", "Old Name", "https://old.example.com/a.png", "UTC", Instant.now());
        when(profileRepository.findById("user-6")).thenReturn(Optional.of(existing));
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Jwt jwt = jwtFor("user-6", "ignored-username", null);

        UpdateProfileRequest request = new UpdateProfileRequest("New Name", null, null);
        Profile profile = profileService.updateProfile(jwt, request);

        assertThat(profile.getDisplayName()).isEqualTo("New Name");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://old.example.com/a.png");
        assertThat(profile.getTimezone()).isEqualTo("UTC");
    }

    private Jwt jwtFor(String subject, String preferredUsername, String name) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (preferredUsername != null) {
            builder.claim("preferred_username", preferredUsername);
        }
        if (name != null) {
            builder.claim("name", name);
        }
        return builder.build();
    }
}
