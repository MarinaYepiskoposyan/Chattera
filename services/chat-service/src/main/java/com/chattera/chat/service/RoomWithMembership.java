package com.chattera.chat.service;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomRole;

/**
 * A room plus the caller's membership role in it, or a {@code null} role
 * if the caller is not a member (only possible for {@code PUBLIC} rooms
 * surfaced by {@code GET /rooms} - see {@link RoomService#listVisibleRooms}).
 */
public record RoomWithMembership(Room room, RoomRole role) {

    public boolean isMember() {
        return role != null;
    }
}
