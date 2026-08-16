package com.chattera.chat.service;

/**
 * Result of {@link RoomService#findOrCreateDirect}: the resolved DIRECT room
 * plus whether this particular call actually created it, so
 * {@code RoomController} can return {@code 201 Created} vs {@code 200 OK}
 * (CHAT-105 AC-1 vs AC-2).
 */
public record DirectRoomOutcome(RoomWithMembership roomWithMembership, boolean created) {
}
