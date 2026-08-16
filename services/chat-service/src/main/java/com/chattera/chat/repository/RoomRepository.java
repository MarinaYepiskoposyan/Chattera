package com.chattera.chat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chattera.chat.domain.Room;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    /**
     * Rooms visible in {@code GET /rooms}: every {@code PUBLIC} room (so
     * users can discover and self-join them) plus any room the caller is
     * already a member of, regardless of type. See
     * {@code RoomService.listVisibleRooms} for the documented listing
     * semantics.
     */
    @Query("""
            SELECT r FROM Room r
            WHERE r.type = com.chattera.chat.domain.RoomType.PUBLIC
               OR r.id IN (SELECT rm.roomId FROM RoomMember rm WHERE rm.userId = :userId)
            ORDER BY r.createdAt DESC
            """)
    List<Room> findVisibleToUser(@Param("userId") String userId);

    /**
     * Row-locks the room for the duration of the transaction. Used by
     * {@code RoomService.leaveRoom} to serialize concurrent leave/ownership-
     * transfer operations on the same room - see that method's Javadoc for
     * the race this guards against.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Looks up a {@code DIRECT} room by its canonical (sorted) participant
     * pair - see {@code RoomService.findOrCreateDirect} (CHAT-105).
     */
    Optional<Room> findByDirectKey(String directKey);
}
