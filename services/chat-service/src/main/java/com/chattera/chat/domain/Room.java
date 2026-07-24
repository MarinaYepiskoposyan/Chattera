package com.chattera.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A chat room. {@code name} is nullable because {@code DIRECT} rooms
 * (CHAT-105) don't have one; CHAT-104 always requires a name for the
 * {@code PUBLIC}/{@code PRIVATE} rooms it creates (enforced at the DTO/
 * validation layer, not here). No mutation methods yet - CHAT-104 has no
 * update-room endpoint.
 */
@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private RoomType type;

    @Column(name = "created_by", nullable = false, updatable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Room() {
        // required by JPA
    }

    public Room(UUID id, String name, RoomType type, String createdBy, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RoomType getType() {
        return type;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
