package com.chattera.chat.service.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a caller tries to self-join a {@code PRIVATE}/{@code DIRECT}
 * room. Per solution-architecture.md, only {@code PUBLIC} rooms are
 * self-joinable; membership in other room types is established by the
 * owner/creator (a richer invite workflow is a post-Sprint-1 follow-up).
 */
public class RoomNotSelfJoinableException extends ChatDomainException {

    public RoomNotSelfJoinableException(UUID roomId) {
        super(HttpStatus.FORBIDDEN, "ROOM_NOT_SELF_JOINABLE", "Room " + roomId + " is not self-joinable");
    }
}
