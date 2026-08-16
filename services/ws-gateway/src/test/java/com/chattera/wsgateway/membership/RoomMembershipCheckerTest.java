package com.chattera.wsgateway.membership;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.chattera.wsgateway.config.WsGatewayProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

/**
 * CHAT-37's mandatory cache-eviction piece: {@link RoomMembershipChecker#evict}
 * must remove exactly the one {@code (sessionId, roomId)} entry so a
 * just-revoked client re-SUBSCRIBEing within the TTL window re-hits
 * chat-service (via {@link MockRestServiceServer}, standing in for it here)
 * instead of being served the stale positive cache result.
 */
class RoomMembershipCheckerTest {

    private static final String BASE_URL = "http://chat-service";

    private MockRestServiceServer mockServer;
    private RoomMembershipChecker checker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        WsGatewayProperties properties = new WsGatewayProperties();
        properties.setChatServiceBaseUrl(BASE_URL);
        checker = new RoomMembershipChecker(builder, properties);
    }

    @Test
    void aPositiveResultIsCachedAndDoesNotReHitChatServiceWithinTheTtl() {
        UUID roomId = UUID.randomUUID();
        mockServer.expect(method(GET))
                .andExpect(requestTo(BASE_URL + "/rooms/" + roomId + "/members/me"))
                .andRespond(withSuccess());

        assertThat(checker.isMember("session-1", roomId, "token")).isTrue();
        assertThat(checker.isMember("session-1", roomId, "token")).isTrue();

        mockServer.verify();
    }

    @Test
    void evictForcesTheNextCallToReHitChatServiceInsteadOfTheStaleCache() {
        UUID roomId = UUID.randomUUID();
        // Membership is revoked server-side between these two calls, so
        // chat-service answers 200 then 404 for the same self-check.
        mockServer.expect(method(GET))
                .andExpect(requestTo(BASE_URL + "/rooms/" + roomId + "/members/me"))
                .andRespond(withSuccess());
        mockServer.expect(method(GET))
                .andExpect(requestTo(BASE_URL + "/rooms/" + roomId + "/members/me"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(checker.isMember("session-1", roomId, "token")).isTrue();

        checker.evict("session-1", roomId);

        assertThat(checker.isMember("session-1", roomId, "token")).isFalse();
        mockServer.verify();
    }

    @Test
    void evictLeavesOtherCachedRoomsForTheSameSessionUntouched() {
        UUID revokedRoomId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();
        mockServer.expect(method(GET))
                .andExpect(requestTo(BASE_URL + "/rooms/" + revokedRoomId + "/members/me"))
                .andRespond(withSuccess());
        mockServer.expect(method(GET))
                .andExpect(requestTo(BASE_URL + "/rooms/" + otherRoomId + "/members/me"))
                .andRespond(withSuccess());
        assertThat(checker.isMember("session-1", revokedRoomId, "token")).isTrue();
        assertThat(checker.isMember("session-1", otherRoomId, "token")).isTrue();

        checker.evict("session-1", revokedRoomId);

        // Only the other room's cache entry survives - no further HTTP call
        // for it, so a strict mockServer.verify() below would fail if this
        // triggered an unexpected request.
        assertThat(checker.isMember("session-1", otherRoomId, "token")).isTrue();
        mockServer.verify();
    }

    @Test
    void evictOfAnUncachedEntryIsANoOp() {
        // No expectations set on mockServer at all - evict must not itself
        // trigger any HTTP call.
        checker.evict("session-1", UUID.randomUUID());
        mockServer.verify();
    }
}
