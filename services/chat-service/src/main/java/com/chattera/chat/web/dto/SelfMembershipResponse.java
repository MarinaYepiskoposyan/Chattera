package com.chattera.chat.web.dto;

import java.util.UUID;

import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;

/**
 * Response shape for {@code GET /rooms/{roomId}/members/me} (CHAT-107) -
 * see solution-architecture.md "Subscribe-time authorization".
 */
public record SelfMembershipResponse(UUID roomId, String userId, RoomRole role) {

    public static SelfMembershipResponse of(RoomMember member) {
        return new SelfMembershipResponse(member.getRoomId(), member.getUserId(), member.getRole());
    }
}
