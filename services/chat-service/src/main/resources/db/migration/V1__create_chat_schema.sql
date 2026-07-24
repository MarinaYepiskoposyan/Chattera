-- CHAT-104: rooms, membership, and messages. created_at columns use
-- TIMESTAMP WITH TIME ZONE from the start (learned from profile-service's
-- V1/V2 correction - see that module's V2 migration).

CREATE TABLE rooms (
    id           UUID PRIMARY KEY,
    name         VARCHAR(255),           -- nullable: DIRECT rooms (CHAT-105) have none
    type         VARCHAR(16) NOT NULL CHECK (type IN ('PUBLIC', 'PRIVATE', 'DIRECT')),
    created_by   VARCHAR(255) NOT NULL,  -- Keycloak sub
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE room_members (
    room_id      UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id      VARCHAR(255) NOT NULL,  -- Keycloak sub
    role         VARCHAR(16) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER', 'MEMBER')),
    joined_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE TABLE messages (
    id           UUID PRIMARY KEY,
    room_id      UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    sender_id    VARCHAR(255) NOT NULL,  -- Keycloak sub
    content      TEXT NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT', 'DELIVERED', 'READ')),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Workhorse index for history fetch + keyset pagination (room_id, before-cursor).
CREATE INDEX idx_messages_room_created_id ON messages (room_id, created_at DESC, id DESC);
