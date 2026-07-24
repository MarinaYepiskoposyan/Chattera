package com.chattera.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chattera.chat.domain.Message;

/**
 * Backs the keyset-paginated history fetch (rides the
 * {@code (room_id, created_at DESC, id DESC)} index - see the V1 migration).
 * Two distinct queries (first page vs. "before" a cursor) rather than one
 * query with an "OR :cursor IS NULL" branch, so each stays a direct,
 * index-friendly range scan.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByRoomIdOrderByCreatedAtDescIdDesc(UUID roomId, Pageable pageable);

    @Query("""
            SELECT m FROM Message m
            WHERE m.roomId = :roomId
              AND (m.createdAt < :cursorCreatedAt
                   OR (m.createdAt = :cursorCreatedAt AND m.id < :cursorId))
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<Message> findByRoomIdBeforeCursor(
            @Param("roomId") UUID roomId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
