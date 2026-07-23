package com.chattera.profile.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Chattera-owned profile data, keyed by the Keycloak {@code sub} claim
 * ({@link #userId}). {@code userId} is assigned by the application from the
 * validated JWT, never generated - a profile only ever exists because a
 * token for that subject was seen. Online/offline status is intentionally
 * not a column here; it is read live from the Redis presence key (see
 * {@code PresenceReader}), never persisted.
 */
@Entity
@Table(name = "profiles")
public class Profile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 255)
    private String userId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Profile() {
        // required by JPA
    }

    public Profile(String userId, String displayName, String avatarUrl, String timezone, Instant createdAt) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.timezone = timezone;
        this.createdAt = createdAt;
    }

    public String getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
