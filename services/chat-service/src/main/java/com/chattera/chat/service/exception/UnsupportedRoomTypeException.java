package com.chattera.chat.service.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when {@code POST /rooms} is asked to create a {@code DIRECT} room.
 * DIRECT room creation is CHAT-105's find-or-create-DM flow, not this
 * general-purpose endpoint.
 */
public class UnsupportedRoomTypeException extends ChatDomainException {

    public UnsupportedRoomTypeException(String message) {
        super(HttpStatus.BAD_REQUEST, "UNSUPPORTED_ROOM_TYPE", message);
    }
}
