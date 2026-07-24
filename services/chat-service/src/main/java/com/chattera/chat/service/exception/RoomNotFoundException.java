package com.chattera.chat.service.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class RoomNotFoundException extends ChatDomainException {

    public RoomNotFoundException(UUID roomId) {
        super(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room " + roomId + " does not exist");
    }
}
