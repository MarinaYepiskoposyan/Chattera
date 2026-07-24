package com.chattera.chat.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.chattera.chat.domain.RoomType;
import com.chattera.chat.service.RoomWithMembership;
import com.chattera.chat.domain.RoomRole;

/**
 * {@code member}/{@code role} reflect the calling user's own relationship to
 * the room ({@code role} is {@code null} when {@code member} is false - only
 * possible for a {@code PUBLIC} room the caller hasn't joined yet).
 */
public record RoomResponse(
        UUID id,
        String name,
        RoomType type,
        String createdBy,
        Instant createdAt,
        boolean member,
        RoomRole role) {

    public static RoomResponse of(RoomWithMembership roomWithMembership) {
        return new RoomResponse(
                roomWithMembership.room().getId(),
                roomWithMembership.room().getName(),
                roomWithMembership.room().getType(),
                roomWithMembership.room().getCreatedBy(),
                roomWithMembership.room().getCreatedAt(),
                roomWithMembership.isMember(),
                roomWithMembership.role());
    }
}
