package com.chattera.chat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomMemberId;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {

    Optional<RoomMember> findByRoomIdAndUserId(UUID roomId, String userId);

    List<RoomMember> findByUserId(String userId);

    /**
     * The earliest-joined remaining member other than {@code excludedUserId}
     * - used to pick who ownership transfers to when the current OWNER
     * leaves (see {@code RoomService.leaveRoom}).
     */
    Optional<RoomMember> findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(UUID roomId, String excludedUserId);
}
