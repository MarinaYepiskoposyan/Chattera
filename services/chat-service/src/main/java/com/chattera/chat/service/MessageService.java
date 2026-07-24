package com.chattera.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chattera.chat.domain.Message;
import com.chattera.chat.domain.MessageStatus;
import com.chattera.chat.repository.MessageRepository;
import com.chattera.chat.web.dto.PostMessageRequest;
import com.chattera.domain.event.RoomMessageCreatedEvent;

/**
 * Message posting and paginated history. Membership authorization is
 * delegated to {@link RoomAccessService} - both operations are member-only
 * per solution-architecture.md.
 */
@Service
public class MessageService {

    /** Hard max page size for history - see solution-architecture.md pagination section. */
    static final int MAX_LIMIT = 50;
    static final int DEFAULT_LIMIT = 50;

    private final RoomAccessService roomAccessService;
    private final MessageRepository messageRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public MessageService(
            RoomAccessService roomAccessService,
            MessageRepository messageRepository,
            ApplicationEventPublisher applicationEventPublisher) {
        this.roomAccessService = roomAccessService;
        this.messageRepository = messageRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Persists the message, then publishes a {@link RoomMessageCreatedEvent}
     * Spring application event. That event is picked up by
     * {@code ChatEventListener} with {@code phase = AFTER_COMMIT}, so the
     * RabbitMQ publish only happens once this transaction has actually
     * committed - "persist-then-publish", per solution-architecture.md.
     */
    @Transactional
    public Message postMessage(String userId, UUID roomId, PostMessageRequest request) {
        roomAccessService.requireMembership(roomId, userId);
        Message message = new Message(
                UUID.randomUUID(), roomId, userId, request.content(), MessageStatus.SENT, Instant.now());
        messageRepository.save(message);
        applicationEventPublisher.publishEvent(new RoomMessageCreatedEvent(
                message.getId(), message.getRoomId(), message.getSenderId(), message.getContent(),
                message.getCreatedAt(), Instant.now()));
        return message;
    }

    @Transactional(readOnly = true)
    public MessagePage getHistory(String userId, UUID roomId, Integer limit, String beforeCursor) {
        roomAccessService.requireMembership(roomId, userId);
        int boundedLimit = boundLimit(limit);
        // Fetch one extra row to know whether more (older) history exists
        // beyond this page, without a separate count query.
        Pageable pageable = PageRequest.of(0, boundedLimit + 1);

        List<Message> page;
        if (beforeCursor == null) {
            page = messageRepository.findByRoomIdOrderByCreatedAtDescIdDesc(roomId, pageable);
        } else {
            MessageCursor.Decoded cursor = MessageCursor.decode(beforeCursor);
            page = messageRepository.findByRoomIdBeforeCursor(roomId, cursor.createdAt(), cursor.id(), pageable);
        }

        boolean hasMore = page.size() > boundedLimit;
        List<Message> trimmed = hasMore ? page.subList(0, boundedLimit) : page;
        String nextCursor = hasMore
                ? MessageCursor.encode(trimmed.get(trimmed.size() - 1).getCreatedAt(), trimmed.get(trimmed.size() - 1).getId())
                : null;
        return new MessagePage(trimmed, nextCursor);
    }

    private static int boundLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(requested, 1), MAX_LIMIT);
    }
}
