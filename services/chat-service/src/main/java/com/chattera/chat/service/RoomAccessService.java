package com.chattera.chat.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.service.exception.NotRoomMemberException;
import com.chattera.chat.service.exception.RoomNotFoundException;

/**
 * Shared room-existence and membership checks used by both
 * {@link RoomService} (leave) and {@link MessageService} (post/read
 * history) - "only members can post/read a room's messages" from
 * solution-architecture.md's authorization boundary.
 */
@Component
public class RoomAccessService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;

    public RoomAccessService(RoomRepository roomRepository, RoomMemberRepository roomMemberRepository) {
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
    }

    public Room requireRoom(UUID roomId) {
        return roomRepository.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
    }

    /**
     * Room existence is checked first so a non-member reliably gets 404 for
     * an unknown room rather than a misleading 403.
     */
    public RoomMember requireMembership(UUID roomId, String userId) {
        requireRoom(roomId);
        return roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new NotRoomMemberException(roomId));
    }

    /**
     * Self-membership check backing {@code GET /rooms/{roomId}/members/me}
     * (CHAT-107, used by ws-gateway's subscribe-time authz). Unlike
     * {@link #requireMembership}, a non-member gets the <b>same</b>
     * {@link RoomNotFoundException} (404) as a non-existent room, per
     * solution-architecture.md: "same response, to avoid room-existence
     * enumeration" - this endpoint is deliberately not the 403-yielding
     * post/read/leave check.
     */
    public RoomMember requireSelfMembership(UUID roomId, String userId) {
        requireRoom(roomId);
        return roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
    }
}
