package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chattera.chat.domain.Room;
import com.chattera.chat.domain.RoomMember;
import com.chattera.chat.domain.RoomRole;
import com.chattera.chat.domain.RoomType;
import com.chattera.chat.repository.RoomMemberRepository;
import com.chattera.chat.repository.RoomRepository;
import com.chattera.chat.service.exception.RoomNotFoundException;
import com.chattera.chat.service.exception.NotRoomMemberException;
import com.chattera.chat.service.exception.RoomNotSelfJoinableException;
import com.chattera.chat.service.exception.UnsupportedRoomTypeException;
import com.chattera.chat.web.dto.CreateRoomRequest;

/**
 * Room creation, listing, join, and leave. See {@link RoomAccessService} for
 * the shared room/membership existence checks used elsewhere (message post
 * and history).
 */
@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;

    /**
     * Self-reference to this bean's own Spring proxy, used only to invoke
     * {@link #insertMembership} through AOP - same reasoning as
     * {@code ProfileService.self}: a plain {@code this.insertMembership(...)}
     * call would bypass the proxy and silently run non-transactionally,
     * defeating both the {@code REQUIRES_NEW} isolation and the ability to
     * recover from a duplicate-key race without poisoning an ambient
     * transaction.
     */
    private final RoomService self;

    public RoomService(RoomRepository roomRepository, RoomMemberRepository roomMemberRepository, @Lazy RoomService self) {
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.self = self;
    }

    @Transactional
    public RoomWithMembership createRoom(String userId, CreateRoomRequest request) {
        if (request.type() == RoomType.DIRECT) {
            throw new UnsupportedRoomTypeException(
                    "DIRECT rooms are not created via this endpoint (see CHAT-105)");
        }
        Room room = new Room(UUID.randomUUID(), request.name(), request.type(), userId, Instant.now());
        roomRepository.save(room);
        roomMemberRepository.save(new RoomMember(room.getId(), userId, RoomRole.OWNER, Instant.now()));
        return new RoomWithMembership(room, RoomRole.OWNER);
    }

    /**
     * {@code GET /rooms} semantics (documented choice, CHAT-104): returns
     * every {@code PUBLIC} room - so users can discover and self-join them,
     * since there is no separate "browse public rooms" endpoint in this
     * ticket's scope - plus every room the caller is already a member of
     * (including {@code PRIVATE}/{@code DIRECT} rooms, which are otherwise
     * invisible). Each entry reports whether the caller is a member and
     * their role, so a client can tell "already in this room" apart from
     * "public, joinable."
     */
    @Transactional(readOnly = true)
    public List<RoomWithMembership> listVisibleRooms(String userId) {
        List<Room> rooms = roomRepository.findVisibleToUser(userId);
        Map<UUID, RoomRole> myRoles = roomMemberRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(RoomMember::getRoomId, RoomMember::getRole));
        return rooms.stream()
                .map(room -> new RoomWithMembership(room, myRoles.get(room.getId())))
                .toList();
    }

    @Transactional
    public RoomWithMembership joinRoom(String userId, UUID roomId) {
        Room room = roomRepository.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
        if (room.getType() != RoomType.PUBLIC) {
            throw new RoomNotSelfJoinableException(roomId);
        }
        Optional<RoomMember> existing = roomMemberRepository.findByRoomIdAndUserId(roomId, userId);
        if (existing.isPresent()) {
            // Join is idempotent: already a member, nothing to do.
            return new RoomWithMembership(room, existing.get().getRole());
        }
        RoomMember member;
        try {
            member = self.insertMembership(roomId, userId, RoomRole.MEMBER);
        } catch (DataIntegrityViolationException duplicateMembership) {
            // Two concurrent joins for the same (roomId, userId) both passed
            // the findByRoomIdAndUserId check above before either committed;
            // the loser's insert hits the (room_id, user_id) primary key and
            // fails here. The winner's row is already committed and visible,
            // so read it back instead of surfacing a bare 500 - same pattern
            // as ProfileService's JIT-provisioning race fix (CHAT-103).
            member = roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                    .orElseThrow(() -> duplicateMembership);
        }
        return new RoomWithMembership(room, member.getRole());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    RoomMember insertMembership(UUID roomId, String userId, RoomRole role) {
        return roomMemberRepository.save(new RoomMember(roomId, userId, role, Instant.now()));
    }

    /**
     * OWNER-leave rule (documented choice, CHAT-104): ownership transfers to
     * the oldest remaining member rather than blocking the OWNER from
     * leaving. Blocking would trap an OWNER who wants to leave behind a
     * not-yet-built "transfer ownership" endpoint; auto-transfer is
     * self-contained and keeps the room usable for whoever remains. If the
     * OWNER is the last member, the room is left with zero members (not
     * deleted) - its message history stays intact and queryable.
     *
     * <p>The whole operation runs under a pessimistic write lock on the
     * {@code rooms} row ({@link RoomRepository#findByIdForUpdate}) so two
     * concurrent leaves on the same room - e.g. the OWNER and the
     * next-oldest member leaving at the same instant - are serialized rather
     * than both reading a stale "oldest remaining member" snapshot and
     * promoting/deleting inconsistently.
     */
    @Transactional
    public void leaveRoom(String userId, UUID roomId) {
        Room room = roomRepository.findByIdForUpdate(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
        RoomMember membership = roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
                .orElseThrow(() -> new NotRoomMemberException(roomId));
        roomMemberRepository.delete(membership);
        if (membership.getRole() == RoomRole.OWNER) {
            roomMemberRepository.findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(room.getId(), userId)
                    .ifPresent(nextOwner -> {
                        nextOwner.setRole(RoomRole.OWNER);
                        roomMemberRepository.save(nextOwner);
                    });
        }
    }
}
