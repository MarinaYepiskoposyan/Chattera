package com.chattera.chat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.chattera.chat.domain.RoomType;

/**
 * {@code POST /rooms} request body. {@code type} accepts the full
 * {@link RoomType} enum at the JSON layer, but {@code DIRECT} is rejected in
 * the service layer with a 400 ({@code UnsupportedRoomTypeException}) -
 * DIRECT room creation is CHAT-105's find-or-create-DM flow.
 */
public record CreateRoomRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull RoomType type) {
}
