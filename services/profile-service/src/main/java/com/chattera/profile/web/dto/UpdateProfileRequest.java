package com.chattera.profile.web.dto;

import jakarta.validation.constraints.Size;

/**
 * PUT /me request body. {@code userId} and {@code createdAt} are not
 * client-editable and are deliberately absent from this DTO. All fields are
 * optional - a client may update just one of displayName/avatarUrl/timezone.
 */
public record UpdateProfileRequest(
        @Size(max = 255) String displayName,
        @Size(max = 1024) String avatarUrl,
        @Size(max = 64) String timezone) {
}
