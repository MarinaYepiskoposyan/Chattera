package com.chattera.chat.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link RoomMember}, matching the
 * {@code (room_id, user_id)} primary key on {@code room_members}. Field
 * names must match {@link RoomMember}'s {@code @Id} fields exactly (JPA
 * {@code @IdClass} contract).
 */
public class RoomMemberId implements Serializable {

    private UUID roomId;
    private String userId;

    public RoomMemberId() {
        // required by JPA
    }

    public RoomMemberId(UUID roomId, String userId) {
        this.roomId = roomId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoomMemberId that)) {
            return false;
        }
        return Objects.equals(roomId, that.roomId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, userId);
    }
}
