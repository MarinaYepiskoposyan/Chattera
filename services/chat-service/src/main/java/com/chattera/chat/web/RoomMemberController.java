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
}
