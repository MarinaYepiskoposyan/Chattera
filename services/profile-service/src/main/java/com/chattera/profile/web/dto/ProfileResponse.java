package com.chattera.profile.web.dto;

import java.time.Instant;

import com.chattera.profile.domain.Profile;
import com.chattera.profile.presence.PresenceStatus;

/**
 * GET/PUT /me response body: persisted profile fields plus presence status
 * read live from Redis (never persisted alongside the profile row).
 */
public record ProfileResponse(
        String userId,
        String displayName,
        String avatarUrl,
        String timezone,
        Instant createdAt,
        String status) {

    public static ProfileResponse of(Profile profile, PresenceStatus status) {
        return new ProfileResponse(
                profile.getUserId(),
                profile.getDisplayName(),
                profile.getAvatarUrl(),
                profile.getTimezone(),
                profile.getCreatedAt(),
                status.name().toLowerCase());
    }
}
