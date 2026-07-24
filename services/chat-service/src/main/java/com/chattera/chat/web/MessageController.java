package com.chattera.chat.web;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chattera.chat.service.MessagePage;
import com.chattera.chat.service.MessageService;
import com.chattera.chat.web.dto.MessageHistoryResponse;
import com.chattera.chat.web.dto.MessageResponse;
import com.chattera.chat.web.dto.PostMessageRequest;

/**
 * Message posting and history for a room. Member-only (see
 * {@code RoomAccessService}); {@code userId} always comes from the
 * validated JWT's {@code sub}.
 */
@RestController
@RequestMapping("/rooms/{roomId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse postMessage(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId, @Valid @RequestBody PostMessageRequest request) {
        return MessageResponse.of(messageService.postMessage(jwt.getSubject(), roomId, request));
    }

    /**
     * Bounded, newest-first, keyset-paginated history - see
     * solution-architecture.md "Message history pagination". {@code limit}
     * defaults to and is capped at 50; {@code before} is the opaque cursor
     * returned as {@code nextCursor} on a previous page, used to load older
     * messages.
     */
    @GetMapping
    public MessageHistoryResponse getHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String before) {
        MessagePage page = messageService.getHistory(jwt.getSubject(), roomId, limit, before);
        return new MessageHistoryResponse(page.messages().stream().map(MessageResponse::of).toList(), page.nextCursor());
    }
}
