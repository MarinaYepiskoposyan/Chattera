package com.chattera.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A user's membership in a room. {@code (roomId, userId)} is the primary
 * key - a user can only have one membership row per room, which is also
 * what makes a duplicate "join" a DB-level constraint violation rather than
 * a silent double row (see {@code RoomService.joinRoom}).
 */
@Entity
@Table(name = "room_members")
@IdClass(RoomMemberId.class)
public class RoomMember {

    @Id
    @Column(name = "room_id", nullable = false, updatable = false)
    private UUID roomId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 255)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private RoomRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected RoomMember() {
        // required by JPA
    }

    public RoomMember(UUID roomId, String userId, RoomRole role, Instant joinedAt) {
        this.roomId = roomId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public String getUserId() {
        return userId;
    }

    public RoomRole getRole() {
        return role;
    }

    public void setRole(RoomRole role) {
        this.role = role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
