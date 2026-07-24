package com.chattera.chat.service.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * Thrown for post/read-history/leave attempts against an existing room by a
 * caller who is not a member of it (403). Room existence is always checked
 * first at the call site - see {@link RoomNotFoundException} for the
 * separate room-doesn't-exist case (404).
 */
public class NotRoomMemberException extends ChatDomainException {

    public NotRoomMemberException(UUID roomId) {
        super(HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER", "Caller is not a member of room " + roomId);
    }
}
