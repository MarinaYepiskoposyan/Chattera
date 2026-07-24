package com.chattera.profile.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PUT /me request body. {@code userId} and {@code createdAt} are not
 * client-editable and are deliberately absent from this DTO. All fields are
 * optional - a client may update just one of displayName/avatarUrl/timezone.
 *
 * <p>{@code avatarUrl} is restricted to http(s) URLs at this write boundary:
 * profile-service only returns it to the owner today, but the field is
 * stored input that other services will eventually render as a link/image
 * for other users, so schemes like {@code javascript:}/{@code data:} are
 * rejected here rather than left to whichever future consumer renders it.
 */
public record UpdateProfileRequest(
        @Size(max = 255) String displayName,
        @Size(max = 1024)
        @Pattern(regexp = "^https?://.+", message = "must be an http(s) URL")
        String avatarUrl,
        @Size(max = 64) String timezone) {
}
