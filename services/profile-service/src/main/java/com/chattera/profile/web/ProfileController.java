package com.chattera.profile.web;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chattera.profile.domain.Profile;
import com.chattera.profile.presence.PresenceReader;
import com.chattera.profile.presence.PresenceStatus;
import com.chattera.profile.service.ProfileService;
import com.chattera.profile.web.dto.ProfileResponse;
import com.chattera.profile.web.dto.UpdateProfileRequest;

/**
 * The authenticated caller's own profile. There is no lookup-by-id endpoint
 * in this ticket's scope - only {@code /me}, resolved from the validated
 * JWT's {@code sub}.
 */
@RestController
@RequestMapping("/me")
public class ProfileController {

    private final ProfileService profileService;
    private final PresenceReader presenceReader;

    public ProfileController(ProfileService profileService, PresenceReader presenceReader) {
        this.profileService = profileService;
        this.presenceReader = presenceReader;
    }

    @GetMapping
    public ProfileResponse getMe(@AuthenticationPrincipal Jwt jwt) {
        Profile profile = profileService.getOrProvision(jwt);
        PresenceStatus status = presenceReader.readStatus(profile.getUserId());
        return ProfileResponse.of(profile, status);
    }

    @PutMapping
    public ProfileResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        Profile profile = profileService.updateProfile(jwt, request);
        PresenceStatus status = presenceReader.readStatus(profile.getUserId());
        return ProfileResponse.of(profile, status);
    }
}
