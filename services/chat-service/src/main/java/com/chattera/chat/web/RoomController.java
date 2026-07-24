package com.chattera.chat.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chattera.chat.service.RoomService;
import com.chattera.chat.web.dto.CreateRoomRequest;
import com.chattera.chat.web.dto.RoomResponse;

/**
 * Room lifecycle: create, list, join, leave. {@code userId} always comes
 * from the validated JWT's {@code sub} - never a client-supplied value.
 */
@RestController
@RequestMapping("/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse createRoom(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRoomRequest request) {
        return RoomResponse.of(roomService.createRoom(jwt.getSubject(), request));
    }

    /**
     * See {@code RoomService.listVisibleRooms} for the documented listing
     * semantics (member rooms + all PUBLIC rooms).
     */
    @GetMapping
    public List<RoomResponse> listRooms(@AuthenticationPrincipal Jwt jwt) {
        return roomService.listVisibleRooms(jwt.getSubject()).stream()
                .map(RoomResponse::of)
                .toList();
    }

    @PostMapping("/{roomId}/join")
    public RoomResponse joinRoom(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        return RoomResponse.of(roomService.joinRoom(jwt.getSubject(), roomId));
    }

    @PostMapping("/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        roomService.leaveRoom(jwt.getSubject(), roomId);
        return ResponseEntity.noContent().build();
    }
}
