package com.chattera.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * CHAT-107 receipt write-back: monotonic SENT -&gt; DELIVERED advance.
     * The {@code status = 'SENT'} guard makes this idempotent - a duplicate
     * or out-of-order {@code MessageDeliveredEvent} that arrives after the
     * message is already DELIVERED/READ updates zero rows rather than
     * downgrading status. Returns the number of rows updated (0 or 1), used
     * to decide whether to publish {@code RoomMessageStatusChangedEvent}.
     */
    @Modifying
    @Query("UPDATE Message m SET m.status = com.chattera.chat.domain.MessageStatus.DELIVERED "
            + "WHERE m.id = :messageId AND m.status = com.chattera.chat.domain.MessageStatus.SENT")
    int markDelivered(@Param("messageId") UUID messageId);

    /**
     * CHAT-107 receipt write-back: "read up to" semantics. Marks every
     * message in {@code roomId} not sent by {@code recipientId}, not already
     * READ, and created at or before the {@code lastReadMessageId} message's
     * {@code createdAt}, as READ. Sprint 1 is DMs-only (see
     * solution-architecture.md "Message status") so {@code recipientId} is
     * unambiguous - a room's non-recipient-sent messages all belong to the
     * single other DM participant. Idempotent by construction (status &lt;&gt;
     * READ guard); if {@code lastReadMessageId} doesn't resolve to a row, the
     * subquery yields no comparable timestamp and zero rows are updated.
     */
    @Modifying
    @Query("""
            UPDATE Message m SET m.status = com.chattera.chat.domain.MessageStatus.READ
            WHERE m.roomId = :roomId
              AND m.senderId <> :recipientId
              AND m.status <> com.chattera.chat.domain.MessageStatus.READ
              AND m.createdAt <= (SELECT last.createdAt FROM Message last WHERE last.id = :lastReadMessageId)
            """)
    int markReadUpTo(
            @Param("roomId") UUID roomId,
            @Param("recipientId") String recipientId,
            @Param("lastReadMessageId") UUID lastReadMessageId);
}
