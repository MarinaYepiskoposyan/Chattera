package com.chattera.chat.domain;

/**
 * {@code DIRECT} is reserved for CHAT-105's reuse of this schema (a room
 * with exactly two members standing in for a direct message) - CHAT-104
 * never creates a {@code DIRECT} room itself, but the type exists here so no
 * migration is needed when CHAT-105 lands.
 */
public enum RoomType {
    PUBLIC,
    PRIVATE,
    DIRECT
}
