package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
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
import com.chattera.chat.service.exception.SelfDmNotAllowedException;
import com.chattera.chat.service.exception.UnsupportedRoomTypeException;
import com.chattera.chat.web.dto.CreateRoomRequest;
import com.chattera.domain.event.RoomMembershipRevokedEvent;

/**
 * Room creation, listing, join, and leave. See {@link RoomAccessService} for
 * the shared room/membership existence checks used elsewhere (message post
 * and history).
 */
@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

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

    public RoomService(
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            ApplicationEventPublisher applicationEventPublisher,
            @Lazy RoomService self) {
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.applicationEventPublisher = applicationEventPublisher;
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
     * Find-or-create a DIRECT room for a user pair (CHAT-105). Looks up the
     * canonical {@code direct_key}; if a room already exists for the pair it
     * is returned as-is (find path, {@code created = false}), otherwise a
     * new DIRECT room plus both membership rows are inserted atomically
     * ({@code created = true}).
     *
     * <p>Same race shape and fix as {@link #joinRoom}: two concurrent
     * find-or-create calls for the same pair can both pass the
     * {@code findByDirectKey} lookup before either commits, so the loser's
     * insert hits the {@code direct_key} unique index and throws
     * {@link DataIntegrityViolationException}. Rather than propagate that,
     * re-read the winner's row - through the {@code self} proxy in a fresh
     * {@code REQUIRES_NEW} transaction, since a failed insert poisons the
     * ambient transaction on real Postgres.
     */
    @Transactional
    public DirectRoomOutcome findOrCreateDirect(String callerSub, String targetSub) {
        if (callerSub.equals(targetSub)) {
            throw new SelfDmNotAllowedException();
        }
        String directKey = canonicalDirectKey(callerSub, targetSub);
        Optional<Room> existing = roomRepository.findByDirectKey(directKey);
        if (existing.isPresent()) {
            return new DirectRoomOutcome(withCallerRole(existing.get(), callerSub), false);
        }
        Room created;
        try {
            created = self.insertDirectRoom(callerSub, targetSub, directKey);
        } catch (DataIntegrityViolationException duplicateDirectKey) {
            // Two concurrent find-or-creates for the same pair both passed
            // findByDirectKey above before either committed; the loser's
            // insert hits the unique direct_key index and lands here. The
            // winner's row is already committed and visible, so read it back
            // instead of surfacing a bare 500 - same pattern as joinRoom.
            Room winner = roomRepository.findByDirectKey(directKey)
                    .orElseThrow(() -> duplicateDirectKey);
            return new DirectRoomOutcome(withCallerRole(winner, callerSub), false);
        }
        return new DirectRoomOutcome(withCallerRole(created, callerSub), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Room insertDirectRoom(String callerSub, String targetSub, String directKey) {
        Room room = new Room(UUID.randomUUID(), null, RoomType.DIRECT, callerSub, Instant.now(), directKey);
        roomRepository.save(room);
        roomMemberRepository.save(new RoomMember(room.getId(), callerSub, RoomRole.MEMBER, Instant.now()));
        roomMemberRepository.save(new RoomMember(room.getId(), targetSub, RoomRole.MEMBER, Instant.now()));
        return room;
    }

    private RoomWithMembership withCallerRole(Room room, String callerSub) {
        RoomMember membership = roomMemberRepository.findByRoomIdAndUserId(room.getId(), callerSub)
                .orElseThrow(() -> new NotRoomMemberException(room.getId()));
        return new RoomWithMembership(room, membership.getRole());
    }

    /** Canonical sorted-pair key for a DIRECT room's {@code direct_key} column. */
    private static String canonicalDirectKey(String subA, String subB) {
        return subA.compareTo(subB) <= 0 ? subA + ":" + subB : subB + ":" + subA;
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
     *
     * <p>Publishes a {@link RoomMembershipRevokedEvent} Spring application
     * event right after the membership row is deleted (CHAT-37). That event
     * is picked up by {@code ChatEventListener} with
     * {@code phase = AFTER_COMMIT} - same "persist-then-publish" pattern as
     * {@code MessageService} - so a rolled-back leave never emits a phantom
     * revoke. Only the leaver is revoked; the OWNER-leave auto-transfer below
     * promotes a new owner but does not revoke their subscription.
     */
    @Transactional
    public void leaveRoom(String userId, UUID roomId) {
        Room room = roomRepository.findByIdForUpdate(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
        RoomMember membership = roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
                .orElseThrow(() -> new NotRoomMemberException(roomId));
        roomMemberRepository.delete(membership);
        applicationEventPublisher.publishEvent(new RoomMembershipRevokedEvent(roomId, userId, Instant.now()));
        if (membership.getRole() == RoomRole.OWNER) {
            roomMemberRepository.findFirstByRoomIdAndUserIdNotOrderByJoinedAtAsc(room.getId(), userId)
                    .ifPresent(nextOwner -> {
                        nextOwner.setRole(RoomRole.OWNER);
                        roomMemberRepository.save(nextOwner);
                    });
        }
    }
}
