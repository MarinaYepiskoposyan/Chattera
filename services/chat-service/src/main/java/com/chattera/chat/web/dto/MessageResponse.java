package com.chattera.chat.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.chattera.chat.domain.Message;
import com.chattera.chat.domain.MessageStatus;

public record MessageResponse(
        UUID id,
        UUID roomId,
        String senderId,
        String content,
        MessageStatus status,
        Instant createdAt) {

    public static MessageResponse of(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRoomId(),
                message.getSenderId(),
                message.getContent(),
                message.getStatus(),
                message.getCreatedAt());
    }
}
