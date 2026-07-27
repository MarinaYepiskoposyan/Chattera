package com.chattera.chat.web.dto;

import java.time.Instant;

import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;

public record MemberResponse(String userId, RoomRole role, Instant joinedAt) {

    public static MemberResponse of(RoomMember member) {
        return new MemberResponse(member.getUserId(), member.getRole(), member.getJoinedAt());
    }
}
