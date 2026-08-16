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

    /**
     * {@code DIRECT}-room-only (CHAT-105): the two participants' Keycloak
     * {@code sub}s in canonical sorted order ({@code min(a,b) + ":" + max(a,b)}),
     * enforced unique at the DB level so at most one DM room exists per pair.
     * Null for {@code PUBLIC}/{@code PRIVATE} rooms.
     */
    @Column(name = "direct_key", updatable = false, length = 511)
    private String directKey;

    protected Room() {
        // required by JPA
    }

    public Room(UUID id, String name, RoomType type, String createdBy, Instant createdAt) {
        this(id, name, type, createdBy, createdAt, null);
    }

    public Room(UUID id, String name, RoomType type, String createdBy, Instant createdAt, String directKey) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.directKey = directKey;
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

    public String getDirectKey() {
        return directKey;
    }
}
