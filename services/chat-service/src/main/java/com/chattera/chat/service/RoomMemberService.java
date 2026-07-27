package com.chattera.chat.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.repository.RoomMemberRepository;

/**
 * Room membership listing. Membership authorization is delegated to
 * {@link RoomAccessService} - member-only per solution-architecture.md, same
 * as {@code MessageService}'s post/history endpoints.
 */
@Service
public class RoomMemberService {

    private final RoomAccessService roomAccessService;
    private final RoomMemberRepository roomMemberRepository;

    public RoomMemberService(RoomAccessService roomAccessService, RoomMemberRepository roomMemberRepository) {
        this.roomAccessService = roomAccessService;
        this.roomMemberRepository = roomMemberRepository;
    }

    @Transactional(readOnly = true)
    public List<RoomMember> listMembers(String userId, UUID roomId) {
        roomAccessService.requireMembership(roomId, userId);
        return roomMemberRepository.findByRoomIdOrderByJoinedAtAsc(roomId);
    }
}
