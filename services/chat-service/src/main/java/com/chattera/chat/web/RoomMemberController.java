package com.chattera.chat.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chattera.chat.service.RoomMemberService;
import com.chattera.chat.web.dto.MemberResponse;
import com.chattera.chat.web.dto.SelfMembershipResponse;

/**
 * Room membership listing. Member-only (see {@code RoomAccessService});
 * {@code userId} always comes from the validated JWT's {@code sub}. Mirrors
 * {@link MessageController}'s auth pattern.
 */
@RestController
@RequestMapping("/rooms/{roomId}/members")
public class RoomMemberController {

    private final RoomMemberService roomMemberService;

    public RoomMemberController(RoomMemberService roomMemberService) {
        this.roomMemberService = roomMemberService;
    }

    @GetMapping
    public List<MemberResponse> listMembers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        return roomMemberService.listMembers(jwt.getSubject(), roomId).stream()
                .map(MemberResponse::of)
                .toList();
    }

    /**
     * Cheap membership self-check (CHAT-107): 200 if the caller is a member,
     * 404 if the room doesn't exist or the caller isn't a member (same
     * response either way - see {@code RoomAccessService.requireSelfMembership}),
     * 401 if the token is missing/invalid. Used by ws-gateway's subscribe-time
     * authorization (forwarding the connected user's own bearer token) -
     * see solution-architecture.md "Subscribe-time authorization".
     */
    @GetMapping("/me")
    public SelfMembershipResponse getSelfMembership(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        return SelfMembershipResponse.of(roomMemberService.getSelfMembership(jwt.getSubject(), roomId));
    }
}
