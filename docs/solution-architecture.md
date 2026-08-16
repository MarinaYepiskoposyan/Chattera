# Solution Architecture — Chattera

## Overview
Chattera will be implemented as a modular real-time messaging platform with separate services for identity, messaging, presence, and file handling.

## High-Level Components
Chattera runs as five deployable, stateless services behind a load balancer (gateway,
ws-gateway, profile, chat, file), plus shared infrastructure and an external identity
provider (Keycloak). Identity/credentials/token issuance are delegated to Keycloak;
no Chattera service handles passwords. Presence is not a standalone service; it is
carried by the ws-gateway and sourced from a Redis presence key.

- Client applications: web and mobile clients
- Identity provider (Keycloak): external, owns credentials, registration, login/logout,
  token issuance, refresh, and SSO sessions. Not a Chattera-built service. Deployment of
  Keycloak itself is owned by devops-engineer; this doc covers the application-side
  integration only.
- Gateway (REST): edge for REST traffic — routing, rate limiting, OAuth2 token validation
- WS-gateway (real-time): WebSocket edge for message delivery, typing, and presence;
  validates the Keycloak token at handshake/CONNECT; writes/expires the Redis presence
  key on connect/disconnect
- Profile service (formerly "auth service"): Chattera-owned profile data (displayName,
  avatarUrl, timezone, bio) that Keycloak does not model well, keyed to the Keycloak
  user id (`sub`); reads the Redis presence key to report online/offline. Pure OAuth2
  resource server — it does not validate passwords or issue tokens.
- Chat service: room and private messaging workflows, message persistence and history
- File service: upload, storage, metadata, and download
- Messaging backbone: event bus for async, event-driven real-time delivery
- Data layer: relational database for users/metadata plus a cache for sessions/presence
- Object storage: file objects, referenced from the database only
- Observability: logging, tracing, metrics, and alerting

## Technology Baseline (Sprint 1)
Decided this session (do not re-litigate; these are the working baseline):
- Language/runtime: Java 21 (LTS), Spring Boot 3.5.x. Spring Boot 4.0 is available and
  may be adopted later; 3.5.x chosen for ecosystem/tutorial breadth during MVP.
- Identity/auth: Keycloak as the external OpenID Connect provider (supersedes the earlier
  self-issued-JWT plan). Keycloak owns registration, credential storage/validation, login,
  logout, token issuance, refresh tokens, and SSO sessions. Every Chattera service is a
  pure OAuth2 resource server (spring-boot-starter-oauth2-resource-server) that validates
  Keycloak-issued JWT access tokens against the realm JWKS — no per-request datastore hit,
  no token issuance in Chattera. Nimbus JwtEncoder, the Chattera login/logout endpoints,
  and the Redis-backed rotating refresh tokens from the earlier plan are dropped: Keycloak
  provides all of that. See "Authentication & Identity (Keycloak)" below for the full flow.
- Relational store: PostgreSQL (schema migrations via Flyway).
- Object storage: MinIO (S3-compatible) in dev, accessed with presigned URLs so file
  bytes never transit the app services; only references/metadata persisted in Postgres.
- Cache/presence: Redis (hot-conversation cache, presence keys). Refresh tokens and SSO
  sessions are no longer Chattera's concern — Keycloak holds them in its own store.
- Real-time: WebSocket traffic is kept in ws-gateway, separate from REST in gateway.
- Build tooling: Maven multi-module monorepo (`platform/*` shared libraries consumed by
  `services/*`). Decided — the repo is built on it and profile-service ships on it. (The
  earlier "Gradle proposed" note is superseded.)
- Event bus: **Kafka via Spring Kafka** (chosen for the target design; see "Chat Rooms &
  Messaging" below). chat-service is the first producer onto the backbone; the
  `EventPublisher` abstraction in `platform/common-messaging` is the broker-agnostic seam
  and is implemented with Kafka as the concrete transport. WebSocket delivery
  (`ws-gateway`) consumes Kafka topics and republish them to STOMP subscribers using its
  local in-memory subscription registry. The earlier RabbitMQ-based broadcast queue model is
  **superseded** and explicitly rejected for the target architecture. See the "Real-time
  delivery — CHAT-107 implementation decisions" subsection below for exact destinations,
  receipt flow, and subscribe-time authz.

Proposed, pending Sprint 1 refinement with developer/qa-engineer:
- Data access: Spring Data JPA/Hibernate for CRUD velocity in Sprint 1; the message
  read/history hot path may later move to jOOQ or JdbcClient (revisit with
  performance-engineer). Final call left to developer.

## Recommended Initial Architecture
1. Use stateless application services behind a load balancer.
2. Separate real-time WebSocket traffic from REST API traffic.
3. Use async message processing for event delivery and notifications.
4. Store message metadata in a durable store and cache hot conversations.
5. Keep file objects in object storage and store references in the database.

## Core Flows
- User sends message to room: client -> API -> chat service -> event bus -> subscribers
- User sends private message: client -> API -> chat service -> event bus -> recipient session
- User uploads file: client -> API -> file service -> object storage -> metadata persistence
- User logs in: client -> Keycloak (Authorization Code + PKCE) -> access/refresh tokens
  -> client calls Chattera services with the access token as a Bearer credential

## Authentication & Identity (Keycloak)
Traces to FR-01 (register / log in / log out / view+update profile / secure web+mobile
access). Keycloak is the single identity authority; Chattera services never see a
password and never mint a token.

### Registration & login flow (OIDC Authorization Code + PKCE)
Authorization Code + PKCE (S256) is the standard modern flow for browser SPAs and native
mobile apps and is the chosen flow for both. No client secret is shipped to the client.

1. Client (web SPA or mobile) starts login and redirects the user agent to Keycloak's
   authorization endpoint with `response_type=code`, `code_challenge`, and
   `code_challenge_method=S256`.
2. The user authenticates on Keycloak's hosted login page. Registration uses Keycloak's
   own hosted registration page (enable "User registration" on the realm). We do NOT
   build a Chattera registration form that forwards credentials — that would reintroduce
   the password handling Keycloak exists to remove. Chattera branding is applied via a
   Keycloak login theme (owned with devops-engineer), so the pages still look like
   Chattera without proxying credentials.
3. Keycloak redirects back to the client's registered `redirect_uri` with an
   authorization `code`.
4. The client exchanges `code` + `code_verifier` at Keycloak's token endpoint and
   receives an access token (JWT), a refresh token, and an ID token.
5. The client calls Chattera REST (`gateway`) and WebSocket (`ws-gateway`) with the
   access token as `Authorization: Bearer <token>`. On WebSocket, the token is passed on
   the STOMP CONNECT frame (or handshake) and validated there.

First-login profile provisioning is just-in-time (JIT): the first time the profile
service sees a valid token for a `sub` it has no row for, it creates a profile record
keyed by `sub` (seeded from token claims such as `preferred_username`/`email`). This
avoids any Keycloak Admin API call or confidential service client for Sprint 1.

### Token validation (every Chattera service is a resource server)
Confirmed: gateway, ws-gateway, chat, file, and profile all use
`spring-boot-starter-oauth2-resource-server` configured against the Keycloak realm. They
validate signature (via the realm JWKS), issuer, expiry, and authorized party locally — no
call to Keycloak per request, no shared session store. Config shape (per service):

```
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://<keycloak-host>/realms/chattera
```

`issuer-uri` lets Spring auto-discover the JWKS and validation metadata from
`/.well-known/openid-configuration`. Keycloak realm/client roles are mapped from the
token's `realm_access.roles` / `resource_access` claims to Spring authorities via a
`JwtAuthenticationConverter` (shared config module). The gateway may additionally
pre-validate at the edge, but each service still validates independently (defense in
depth; services are not trusted to be reachable only via the gateway).

**Client-of-origin validation — `azp`, not `aud` (decided CHAT-28).** The
signature/issuer/expiry checks above do not, on their own, constrain *which realm client*
a token was minted for: any token from the `chattera` realm — including one issued to a
different, lower-trust client — would otherwise be accepted by every service. Keycloak in
its default configuration (which is what this realm uses — no per-service "Audience"
protocol mappers) populates `aud` with `"account"` for every client, so `aud` cannot
distinguish Chattera's clients; the claim that identifies the requesting client is `azp`
(authorized party). Rather than register each resource server as a Keycloak client and add
audience mappers (a real per-service `aud` — over-engineering for Sprint 1's single trust
domain, where every service trusts the same realm and the same frontend client set), the
shared `common-security` library adds an `azp` allowlist validator to the `JwtDecoder`,
auto-configured so every service inherits it the same way the role converter is inherited
today. Accepted client ids default to the realm's sanctioned set (`chattera-web`,
`chattera-mobile`, and the dev/test-only `chattera-test-client`) and are overridable per
environment via `chattera.security.jwt.accepted-client-ids`. See
[`docs/guides/how-security-works.md`](guides/how-security-works.md) §6.2. Revisit this
(add real per-service audiences) only if services need to be segmented into distinct trust
domains — not a Sprint 1 requirement.

### Refresh, session, and logout
Keycloak fully replaces the earlier Redis-backed refresh-token design. There is no
Chattera token store to rotate or revoke.
- Refresh: the client refreshes its access token directly against Keycloak's token
  endpoint using the Keycloak refresh token. Access-token lifetime is short (e.g. ~5 min,
  tuned by devops-engineer); refresh/SSO-session lifetimes are Keycloak realm settings.
- Logout: OIDC RP-initiated logout — the client redirects to Keycloak's
  `end_session_endpoint`, which ends the Keycloak SSO session and invalidates the refresh
  token. Chattera-side logout has no token to clear; it only needs to drop client state
  and let the ws-gateway close the socket (a token that can no longer be refreshed simply
  expires within the access-token TTL). Optional hardening later: Keycloak Back-Channel
  Logout so ws-gateway proactively terminates live sockets on logout — deferred, noted.

### Realm & client setup (developer-actionable)
One realm: `chattera`. Clients:
- `chattera-web` — public client. Standard flow (Authorization Code) enabled, PKCE
  required (`pkce.code.challenge.method=S256`), implicit/direct-access-grants disabled.
  Set valid redirect URIs and Web Origins (CORS) to the SPA origin(s).
- `chattera-mobile` — public client. Standard flow + PKCE required; valid redirect URIs
  use the app's custom scheme / claimed https deep link. Direct access grants disabled.
- No confidential client is required for Sprint 1: Chattera services are resource servers
  that only validate tokens, and JIT provisioning removes the need for Admin-API calls.
  Introduce a confidential client only if we add (a) a BFF for the web app, or (b) a
  service that must call the Keycloak Admin API or act via a service account.

Open decision (web token handling) — recommend option A for Sprint 1:
- Option A (baseline): `chattera-web` as a public SPA client, tokens held in browser
  memory, silent refresh via Keycloak. Simplest, standard, no extra service.
- Option B (hardening): a Backend-for-Frontend holding a confidential client; browser
  gets only an httpOnly session cookie and tokens never reach JS. Stronger against XSS
  token theft, but adds a stateful edge component. Revisit post-Sprint-1.

### Presence (unaffected by the IdP choice)
Confirmed unchanged: ws-gateway writes `presence:{userId}` to Redis on connect and
expires it on disconnect; the profile service reads that key to report online/offline.
The `userId` is the Keycloak `sub` from the validated token — the only coupling to
Keycloak is that the identifier now comes from the token subject.

## Chat Rooms & Messaging (CHAT-104 / FR-02)
Design baseline for chat-service. Traces to FR-02 (create/join/leave rooms, multiple
participants, message history) with deliberate boundaries to FR-03 (DMs, CHAT-105) and
FR-05 (real-time + delivered/read status, CHAT-107). Follows the profile-service
precedent: an independent OAuth2 resource server, Flyway-owned schema, JPA for CRUD.

### Event bus decision — Kafka (Spring Kafka)
The event backbone is now Kafka instead of RabbitMQ. The Kafka choice is driven by the need
for durable, replayable event streams and stable partitioning by `roomId` / `userId`, while
PostgreSQL remains the source of truth for message history and room state.
Options weighed:
- **Kafka (chosen).** Chat delivery is both a fan-out problem and a durable-event-stream
  problem. Kafka gives us partitioned topics, replayable event history, multi-subscriber
  consumer groups, and a stable ordering model per partition. `roomId` can be used as the
  partition key so all events for one room land in one log; ws-gateway can consume a
  dedicated consumer group for delivery and another for receipts/read-state. Spring Kafka
  fits the app architecture cleanly and keeps the event model explicit across services.
- **RabbitMQ (rejected for the target design).** RabbitMQ is simpler for a single fan-out
  broker, but it is less suitable for long-lived event replay and cross-service stream
  processing. It also couples the real-time path more tightly to queue semantics than the
  Chattera target design prefers.
- **Redis Streams/Pub-Sub (rejected).** Pub/Sub is fire-and-forget and loses messages on
  transient downtime; Streams would work but still concentrate too much cache/presence/bus
  logic in Redis and give weaker semantics than Kafka for a multi-consumer event backbone.

**The bus is transient delivery, not the message store.** PostgreSQL remains the single
source of truth for message history. A message is persisted (committed) *before* it is
published; a dropped/undelivered event never loses data because clients re-fetch recent
history on (re)connect. Kafka durability reduces the risk of lost events during consumer
restarts and makes replay feasible without forcing a rebuild from the database.
Publish is best-effort: a publish failure is logged and must **not** fail the REST write.
A transactional outbox (publish exactly-once, tied to the DB commit) is a later hardening
step and should be introduced once the event model is stable.

### Kafka event catalog and examples
All events are emitted as a common envelope so consumers can handle topics uniformly while
preserving a topic-specific payload. The envelope keeps a stable contract for every producer;
consumers do not need to inspect the topic to know the metadata fields.

**Envelope**
```json
{
  "eventId": "7dfe8af2-4f58-4c5d-9acf-9a4f1aa43117",
  "eventType": "ROOM_MESSAGE_CREATED",
  "aggregateType": "MESSAGE",
  "aggregateId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
  "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
  "userId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
  "occurredAt": "2026-08-16T12:00:00Z",
  "version": 1,
  "payload": {
    "messageId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
    "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
    "senderUserId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
    "content": "hello team",
    "messageType": "TEXT",
    "createdAt": "2026-08-16T12:00:00Z"
  }
}
```

**1) Room message created**
- Topic: `chattera.events.room-message-created`
- Key: `roomId`
- Producer: `chat-service`
- Consumer: `ws-gateway` (push to room subscribers), future analytics/moderation consumers

```json
{
  "eventId": "7dfe8af2-4f58-4c5d-9acf-9a4f1aa43117",
  "eventType": "ROOM_MESSAGE_CREATED",
  "aggregateType": "MESSAGE",
  "aggregateId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
  "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
  "userId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
  "occurredAt": "2026-08-16T12:00:00Z",
  "version": 1,
  "payload": {
    "messageId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
    "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
    "senderUserId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
    "content": "hello team",
    "messageType": "TEXT",
    "createdAt": "2026-08-16T12:00:00Z"
  }
}
```

**2) Message delivered**
- Topic: `chattera.events.room-message-delivered`
- Key: `messageId` (or recipient user id when delivery is tracked per recipient)
- Producer: `ws-gateway`
- Consumer: `chat-service` (updates message status in Postgres)

```json
{
  "eventId": "8ed15b68-6d4f-46a1-9d22-69478d724b25",
  "eventType": "ROOM_MESSAGE_DELIVERED",
  "aggregateType": "MESSAGE",
  "aggregateId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
  "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
  "userId": "user_bc5313f4-2c40-44d3-bac0-3f431d580d6b",
  "occurredAt": "2026-08-16T12:00:10Z",
  "version": 1,
  "payload": {
    "messageId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
    "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
    "recipientUserId": "user_bc5313f4-2c40-44d3-bac0-3f431d580d6b",
    "deliveredAt": "2026-08-16T12:00:10Z"
  }
}
```

**3) Message read**
- Topic: `chattera.events.room-message-read`
- Key: `messageId` or `readerUserId`
- Producer: `ws-gateway`
- Consumer: `chat-service` (persists read receipt state)

```json
{
  "eventId": "2b29e14c-0dbd-42b9-b53d-54f92003a7de",
  "eventType": "ROOM_MESSAGE_READ",
  "aggregateType": "MESSAGE",
  "aggregateId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
  "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
  "userId": "user_bc5313f4-2c40-44d3-bac0-3f431d580d6b",
  "occurredAt": "2026-08-16T12:00:32Z",
  "version": 1,
  "payload": {
    "messageId": "msg_01962d0b-80d1-4d7b-8c7a-60c97f597fcb",
    "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
    "readerUserId": "user_bc5313f4-2c40-44d3-bac0-3f431d580d6b",
    "readAt": "2026-08-16T12:00:32Z"
  }
}
```

**4) Room member joined**
- Topic: `chattera.events.room-member-joined`
- Key: `roomId`
- Producer: `chat-service`
- Consumer: `ws-gateway` / future notifications / moderation tooling

```json
{
  "eventId": "35d7b61d-f4b8-491d-9af2-0d09bf2d8d95",
  "eventType": "ROOM_MEMBER_JOINED",
  "aggregateType": "ROOM_MEMBER",
  "aggregateId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1:user_90dbd75c-7d39-4e4f-b215-a1be7e0805d4",
  "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
  "userId": "user_90dbd75c-7d39-4e4f-b215-a1be7e0805d4",
  "occurredAt": "2026-08-16T12:01:00Z",
  "version": 1,
  "payload": {
    "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
    "userId": "user_90dbd75c-7d39-4e4f-b215-a1be7e0805d4",
    "role": "MEMBER",
    "joinedAt": "2026-08-16T12:01:00Z"
  }
}
```

**5) Presence updated**
- Topic: `chattera.events.presence-updated`
- Key: `userId`
- Producer: `ws-gateway`
- Consumer: `profile-service` or any presence-aware component

```json
{
  "eventId": "f5ee2b8d-82ab-4826-ad52-b8174788dbd7",
  "eventType": "PRESENCE_UPDATED",
  "aggregateType": "PRESENCE",
  "aggregateId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
  "roomId": null,
  "userId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
  "occurredAt": "2026-08-16T12:02:05Z",
  "version": 1,
  "payload": {
    "userId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
    "online": true,
    "lastSeenAt": "2026-08-16T12:02:05Z",
    "source": "WS_GATEWAY"
  }
}
```

**6) File uploaded**
- Topic: `chattera.events.file-uploaded`
- Key: `fileId` or `userId`
- Producer: `file-service`
- Consumer: `chat-service` / notification consumers / future feed processors

```json
{
  "eventId": "d8b0fca7-8b96-4fa8-ae4c-eed9efec1fa0",
  "eventType": "FILE_UPLOADED",
  "aggregateType": "FILE",
  "aggregateId": "file_44eb0c5d-7df4-4a01-9d33-77d406c35b92",
  "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
  "userId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
  "occurredAt": "2026-08-16T12:03:00Z",
  "version": 1,
  "payload": {
    "fileId": "file_44eb0c5d-7df4-4a01-9d33-77d406c35b92",
    "roomId": "room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1",
    "uploadedByUserId": "user_7f8f4f35-9d0d-47f2-a662-76ed13001a2d",
    "objectKey": "uploads/room_1b0db96e-671a-4b40-a0cc-17e9fe58dff1/file_44eb0c5d-7df4-4a01-9d33-77d406c35b92",
    "mimeType": "image/png",
    "sizeBytes": 256000,
    "storedAt": "2026-08-16T12:03:00Z"
  }
}
```

These event examples define the default contract for the Kafka-based backbone. Every topic
uses a stable partition key (`roomId`, `userId`, or aggregate id) so message ordering and
fan-out stays predictable while keeping Postgres as the source of truth for durable domain
state.

### Scope split: CHAT-104 vs CHAT-107 (confirmed)
- **CHAT-104 owns** the REST + persistence + producer side, and is independently
  buildable/testable without CHAT-107:
  - REST: create room, join room, leave room, list a room's members, post a message,
    fetch message history.
  - Persistence: rooms, room membership, messages (schema below).
  - Producing: after the message row commits, publish a `RoomMessageCreated` event to its
    Kafka topic (`chattera.events.room-message-created.{roomId}`, auto-created on first
    publish — see "Event bus decision — Kafka" above). If no consumer is subscribed yet
    (CHAT-107 not built), the event is simply not delivered — acceptable, because history
    is durable in Postgres.
- **CHAT-107 owns** the consumer/real-time side: ws-gateway subscribing to the bus and
  pushing to live sockets, plus the delivered/read receipt round-trip.

CHAT-104 acceptance is verified entirely over REST (POST message returns the persisted
message; GET history returns it in order). No WebSocket dependency to close the ticket.

### Message status (FR-05 boundary)
The `messages` table carries a `status` column now so no migration is needed later, but
CHAT-104 only ever writes `SENT` (server-accepted + persisted). `DELIVERED`/`READ`
transitions are inherently real-time and belong to CHAT-107. For a single recipient (DMs,
CHAT-105) a single per-message status is sufficient. **Per-recipient read receipts in
multi-participant rooms ("read by whom?") are a genuinely larger, fan-out N:M concern** —
that needs a separate `message_receipts(message_id, user_id, status)` table and is flagged
to CHAT-107, likely limited to DMs for Sprint 1. Do not build room-level per-recipient
receipts in CHAT-104. The concrete DELIVERED/READ write-back mechanism (broker-mediated
receipt events, triggers, persistence rule) is specified in "Real-time delivery — CHAT-107
implementation decisions" §2 below.

### Data model (chat-service schema, Flyway)
**A direct message is a specialization of a room, not a separate concept.** DMs (CHAT-105)
are modelled as a room with `type = 'DIRECT'` and exactly two members, so they reuse
`room_members`, `messages`, and the history/pagination path rather than a parallel schema.
The `rooms.type` CHECK already includes `DIRECT`, so CHAT-105 adds no change to these tables
— it is a thin layer on top of CHAT-104, differing only in these DM-specific rules:
- **No name** — `rooms.name` is null for DIRECT (hence nullable); the UI shows the other
  participant's display name.
- **Exactly two members, set at creation** — not self-joinable (PRIVATE/DIRECT are not
  self-joinable per the authorization rules below). Roles are symmetric — both `MEMBER`;
  ownership is meaningless for a DM.
- **One DM per user pair (uniqueness)** — CHAT-105 adds a `direct_key` column populated
  only for DIRECT rooms = the two `sub`s in canonical (sorted) order
  (`min(a,b) + ':' + max(a,b)`), with a `UNIQUE` index; null/unused for PUBLIC/PRIVATE.
  This enforces pair-uniqueness at the DB level.
- **Find-or-create, idempotent** — the DM endpoint computes `direct_key`; if a DIRECT room
  for the pair exists it is returned, else created. Messaging/history then route through the
  same `/rooms/{roomId}/messages` endpoints — no duplicate DM rooms.
- **Self-DM blocked** — reject when the target user equals the caller `sub` (AC-4).

```
rooms
  id           UUID PRIMARY KEY
  name         VARCHAR(255)        -- nullable for DIRECT rooms
  type         VARCHAR(16) NOT NULL CHECK (type IN ('PUBLIC','PRIVATE','DIRECT'))
  created_by   VARCHAR(255) NOT NULL          -- Keycloak sub
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()

room_members
  room_id      UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE
  user_id      VARCHAR(255) NOT NULL           -- Keycloak sub
  role         VARCHAR(16) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER','MEMBER'))
  joined_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  PRIMARY KEY (room_id, user_id)

messages
  id           UUID PRIMARY KEY
  room_id      UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE
  sender_id    VARCHAR(255) NOT NULL           -- Keycloak sub
  content      TEXT NOT NULL
  status       VARCHAR(16) NOT NULL DEFAULT 'SENT'
                 CHECK (status IN ('SENT','DELIVERED','READ'))
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX (room_id, created_at DESC, id DESC)   -- serves history fetch + keyset pagination
```

`created_at` is `TIMESTAMPTZ` (following the V2 profiles correction). `id` is a UUID
assigned by the app/DB. The composite index is the workhorse for the history query below.

### Authorization boundary
chat-service is its own OAuth2 resource server, wired exactly like profile-service
(`common-security` auto-configures JWT validation, the realm-role→authority converter, and
the `azp` allowlist; the service only defines its `SecurityFilterChain` permitting
`/actuator/health` and authenticating everything else). `userId` is always the validated
JWT `sub` via `@AuthenticationPrincipal Jwt` — never a client-supplied body/param.

Room-scoped rules, enforced inside chat-service (services are not trusted to be reachable
only via a gateway):
- **Post / read history / list members / leave**: caller must be a member of the room.
  Non-members get 403.
- **Join**: `PUBLIC` rooms are self-joinable by any authenticated user. `PRIVATE` and
  `DIRECT` rooms are not self-joinable — membership is established by the creator/owner
  (an owner-adds-member action) or, for DMs, by the find-or-create-DM flow in CHAT-105. A
  richer invite workflow for PRIVATE rooms is a post-Sprint-1 follow-up, noted.
- **Create**: any authenticated user; the creator is inserted as the `OWNER` member in the
  same transaction as the room row.

### Client entry point
Clients call chat-service **directly** on its port (8083), the same as the profile-service
precedent. `gateway` is still a routing-less scaffold with no ticket to add routing, so
gateway routing is **not** in CHAT-104 scope. Design the REST paths to be gateway-friendly
so a future gateway can front them without rework — e.g. `POST /rooms`,
`POST /rooms/{roomId}/members` (join), `DELETE /rooms/{roomId}/members/me` (leave),
`GET /rooms/{roomId}/members`, `POST /rooms/{roomId}/messages`,
`GET /rooms/{roomId}/messages` (history). A future gateway can route `/api/chat/**` to these
unchanged.

### Inbound message transport — REST to send, WebSocket to receive (decided)
Sending a message is a **REST POST to chat-service**; the WebSocket (ws-gateway) is
**receive-only** for messages. The client therefore runs two channels at once: REST for
writes/commands, the socket for real-time delivery. Rationale:
- **Decouples CHAT-104 from CHAT-107** — the write path is exercisable over plain HTTP with
  no WebSocket infrastructure, which is what makes CHAT-104 independently shippable/testable.
- **Respects the service boundary** — chat-service is the sole owner of writes (authz +
  persist + publish); a socket-send would force ws-gateway to persist or to call
  chat-service, dragging the write path into the delivery edge.
- **Command/ack semantics** — a send has a definite result; REST gives synchronous
  `201`/`400`/`403`/`413`/`429` with standard middleware (rate limiting, idempotency).
- **Auth** — REST re-validates the token per request (cheap, local JWT check); a long-lived
  socket is validated once at CONNECT and can outlive the token TTL.
- Publish happens **after the DB commit** (e.g. a transaction `afterCommit` hook), never
  inside the transaction — publishing inside a tx that then rolls back would emit a phantom
  message. The tiny commit→publish crash gap is what the deferred transactional outbox
  would close.

Noted alternative (deferred): send over the socket (STOMP `SEND`) for lower send latency /
single connection. It moves the write entry point into ws-gateway and couples it to
chat-service, so revisit only if performance-engineer shows a send-latency target REST
misses. Because producers publish through the `EventPublisher` abstraction, adding a
socket-send path later lands in the same persist+publish logic — a contained change.

### Real-time delivery runtime & the ws-gateway ↔ chat-service relationship
The two services have **no direct relationship** — they never call each other. Their only
link is Kafka: chat-service is the **producer**, ws-gateway is the **consumer**. This
decoupling is deliberate and is what lets both tiers be stateless and scale independently.
- **chat-service** = writer / system of record: REST writes, authz, business rules,
  Postgres persistence, and publishing events. **ws-gateway** = delivery edge: holds live
  sockets, tracks presence, pushes events to clients. No business logic, no DB.
- **The contract between them is the event, not an API** — its fields + partition key,
  defined via `common-messaging`/`common-domain` (`DomainEvent`, e.g. `RoomMessageCreated`).
  The event is **self-contained** (id, sender, content, timestamp, status), so ws-gateway
  forwards it without querying Postgres or calling chat-service.
- **End-to-end loop** (through both, but they never touch directly):
  `Client --REST POST--> chat-service --publish--> Kafka --consume--> ws-gateway --WS push--> Client`.
  Any chat-service instance can publish; any ws-gateway instance can consume; Kafka bridges
  any-to-any using consumer groups and partitions.
- **ws-gateway statelessness** — a live socket is unavoidably transient state on one
  instance, but no *authoritative* state lives there (history→Postgres, presence→Redis,
  routing→Kafka, identity→the JWT). If an instance dies, the client reconnects to **any**
  other instance (no sticky sessions), re-CONNECTs, re-SUBSCRIBEs, and re-fetches recent
  history — nothing is lost. A message sent during the reconnect gap is missed on the socket
  but recovered from history, which is again why persist-first + Postgres-as-source-of-truth
  matters. Kafka consumer groups allow ws-gateway pods to scale horizontally without a
  per-pod queue-per-subscription topology.

### Message history pagination (applies to BOTH rooms CHAT-104 and DMs CHAT-105)
Decided once, consistently. History is returned **bounded, newest-first, with an optional
keyset (cursor) parameter for loading older messages** — not offset pagination, and not an
unbounded "return everything" fetch.
- `GET /rooms/{roomId}/messages?limit=50&before=<cursor>`
- `limit` default 50, hard max 50 (a bound is mandatory regardless — an unbounded response
  on a busy room is a correctness/performance defect, not a missing feature).
- `before` is an opaque keyset cursor over `(created_at, id)`; omit it for the first
  (most-recent) page. The server returns the next cursor when more history exists.
- Keyset (not offset) because it rides the `(room_id, created_at DESC, id DESC)` index and
  stays correct/cheap as history grows — offset degrades and is the thing to avoid.
- Rationale for including a cursor rather than "most-recent-only, no pagination": the
  bounded most-recent fetch is required either way, so exposing a single `before` cursor is
  a trivial increment on the mandatory cap and avoids a certain near-term rework ("load
  older messages" is core chat UX). Heavier history features (full-text search,
  jump-to-date, unread-anchored loading) remain deferred.

### Real-time delivery — CHAT-107 implementation decisions (STOMP destinations, receipts, subscribe authz)
Pins down the three implementation-level decisions CHAT-107 needs before build. The
big-picture runtime is already settled above ("Real-time delivery runtime & the ws-gateway ↔
chat-service relationship") and in CHAT-24 §3 ("broadcast queue per ws-gateway pod +
app-side room→session filtering"); this subsection does **not** re-derive it, it fills in the
concrete strings, event shapes, and trigger conditions so developer can implement directly.

**Broker wiring recap (the constraint every decision below respects).** ws-gateway uses
Spring's **in-memory simple broker** (`enableSimpleBroker("/topic")`, **not**
`enableStompBrokerRelay` — the relay-per-subscription model is the rejected one). Every
ws-gateway pod runs a Kafka consumer (`@KafkaListener(topicPattern = "chattera.events.*")`)
that wildcard-subscribes to the shared `chattera.events.*` topic namespace and dispatches by
payload type via `@KafkaHandler`. The consumer republishes each event into the local simple
broker via `SimpMessagingTemplate`; the simple broker's per-pod subscription registry **is**
the "in-memory roomId→local-session map" from CHAT-24 §3 — developer does not hand-roll that
map, Spring maintains it. ws-gateway never touches Postgres and never calls chat-service on
the message hot path.

  **Current caveat, tracked as CHAT-114, not fixed here.** This still assumes every pod sees
  every event, which the earlier RabbitMQ broadcast-queue design guaranteed by construction.
  The actual `KafkaEventPublisher`/`ChatteraMessagingProperties` wiring does not yet reproduce
  that guarantee: it publishes each event to a distinct, auto-created topic per room per
  event type (e.g. `chattera.events.room-message-created.<roomId>`) rather than a bounded,
  partitioned topic set keyed by `roomId`/`userId`, and every ws-gateway pod currently shares
  one Kafka consumer group id (`ws-gateway`) — which is the pattern for *splitting* partitions
  across a group, not fanning the same event out to every member. At Sprint 1's single-pod
  scale this is invisible; it must be resolved before CHAT-24's multi-pod ws-gateway design
  (floor of ≥6 pods) is real. See §3 below and CHAT-114.

  the single delivery destination for *both* room messages and DMs. A DM is a room
  (`type='DIRECT'`, CHAT-105), so it reuses the exact same destination, event, routing key,
  and authz as any other room — there is **no** separate per-user `/user/queue/...` delivery
  destination for messages. `{roomId}` is the concrete room UUID; the substituted destination
  (e.g. `/topic/rooms.7f3e...`) is an opaque exact-match string to the simple broker, so **no
  custom `PathMatcher` is required** for subscribe/deliver.
- **What the client subscribes to.** On CONNECT the client fetches its room list over REST and
  SUBSCRIBEs to `/topic/rooms.{roomId}` for each room/DM it belongs to and wants live. Opening
  a new conversation adds one SUBSCRIBE.
- **Consistency with the topic namespace.** The client-facing STOMP destination is a
  simple-broker concept, fully decoupled from Kafka topic naming: naming rooms
  `/topic/rooms.{roomId}` creates no Kafka topic or partition — it only creates a per-session
  entry in the pod's in-memory simple broker registry. What the actual Kafka topic count looks
  like (currently one topic per room per event type, per the caveat above) is a separate
  concern from STOMP destination naming, and is the scaling gap tracked as CHAT-114 (§3), not
  something the destination-naming scheme itself causes.
- **Not-currently-subscribed conversations** (e.g. a brand-new DM opened by someone else after
  you connected) are picked up on the next room-list/history refetch, not pushed live. A
  dedicated real-time "new-conversation" nudge (FR-05 in-app notifications) is its **own**
  concern and is **deferred** to the notifications ticket — it is explicitly out of CHAT-107
  scope so ws-gateway stays free of room-membership knowledge.

#### 2. Delivered/read status write-back — broker-mediated (option a), never a direct call
ws-gateway must never write Postgres and never call chat-service synchronously on the hot
path. So status write-back is **broker-mediated in both directions** (mirrors the
persist-then-publish producer side): ws-gateway **publishes** a receipt event onto
`chattera.events`; **chat-service consumes it and applies the Postgres update**. ws-gateway
gets the `EventPublisher` from `common-messaging` exactly as chat-service does (publish is
best-effort/never-throws per that contract).

**New shared events (add to `common-domain`, records implementing `DomainEvent`, mirroring
`RoomMessageCreatedEvent`):**

```
// Published by ws-gateway, consumed by chat-service. Topic: chattera.events.receipt.delivered
MessageDeliveredEvent(UUID messageId, UUID roomId, String recipientId,
                      Instant deliveredAt, Instant occurredAt)

// Published by ws-gateway, consumed by chat-service. Topic: chattera.events.receipt.read
// messageId = the recipient's last-read message; chat-service applies "read up to" semantics.
MessageReadEvent(UUID messageId, UUID roomId, String recipientId,
                 Instant readAt, Instant occurredAt)

// Published by chat-service AFTER it persists a status change, consumed by ws-gateway.
// Topic: chattera.events.room-message-status-changed.<roomId> (same naming family as
// RoomMessageCreatedEvent, covered by ws-gateway's chattera.events.* wildcard subscription
// and delivered on /topic/rooms.{roomId} like any room event).
// status is "DELIVERED" | "READ" (kept a String to avoid depending on chat-service's
// MessageStatus enum from a shared module; documented allowed values).
RoomMessageStatusChangedEvent(UUID messageId, UUID roomId, String status,
                              String recipientId, Instant occurredAt)
```

**Topics and consumption:**
- ws-gateway publishes receipts to `chattera.events.receipt.delivered` /
  `chattera.events.receipt.read`.
- **chat-service** consumes them via `ReceiptEventListener`
  (`@KafkaListener(topicPattern = "chattera.events.*")`, `@KafkaHandler`-by-payload-type
  dispatch). ws-gateway's own `RoomEventBroadcastListener` uses the identical wildcard
  pattern, so both services' listeners are assigned every topic under the namespace,
  including each other's event types — Spring's class-level `@KafkaHandler` dispatch throws
  `KafkaException("No method found for <payload type>")` for any consumed record whose
  payload type has no matching handler, so each listener declares a no-op
  `@KafkaHandler(isDefault = true)` to absorb the event types it doesn't act on (e.g.
  `ReceiptEventListener` no-ops on `RoomMessageCreatedEvent` and friends;
  `RoomEventBroadcastListener` no-ops on `MessageDeliveredEvent`/`MessageReadEvent`). This is
  a workable stopgap, not the target design — the real fix (narrower per-service topic
  patterns instead of one shared wildcard) is the same shared-namespace root cause as the
  rest of this section and remains tracked under CHAT-114.
- After chat-service persists, it publishes `RoomMessageStatusChangedEvent` to
  `chattera.events.room-message-status-changed.<roomId>`, which ws-gateway picks up on its
  wildcard subscription and delivers on `/topic/rooms.{roomId}` — so the **original sender**
  (subscribed to that topic) sees the tick update live. This one extra hop applies only to
  low-volume, DM-only receipts, never to the message hot path.

**Persistence rule (monotonic, idempotent).** chat-service advances status only, never
downgrades, so out-of-order or duplicate receipts are safe:
- On `MessageDeliveredEvent`:
  `UPDATE messages SET status='DELIVERED' WHERE id=:messageId AND status='SENT'`.
- On `MessageReadEvent` (read-up-to, resolved server-side since chat-service owns `messages`):
  `UPDATE messages SET status='READ' WHERE room_id=:roomId AND sender_id<>:recipientId
   AND status<>'READ' AND created_at <= (SELECT created_at FROM messages WHERE id=:messageId)`.
- Publish `RoomMessageStatusChangedEvent` **only if a row was actually advanced** (avoid
  echoing no-op receipts). For READ, publish one event carrying the last-read `messageId`; the
  client applies up-to semantics (mark all messages `<= messageId` read). For DELIVERED,
  publish for that single `messageId`.
- Sprint 1 is **DMs only** for receipts (single recipient → the single `messages.status`
  column is sufficient). Per-recipient room read receipts still need the deferred
  `message_receipts(message_id, user_id, status)` table (CHAT-104 §"Message status") and are
  **not** built here.

**What triggers DELIVERED vs READ:**
- **DELIVERED = server-side, automatic, no client action.** When a ws-gateway pod
  **successfully pushes** a `RoomMessageCreatedEvent` to a locally-held socket whose
  `userId != senderId` (i.e. an actual recipient, not the sender's own echo), the pod emits
  `MessageDeliveredEvent(messageId, roomId, recipientId=thatUser, ...)`. This is the
  "message reached a currently-connected socket" trigger; the recipient client does nothing.
- **READ = client-signalled**, because "rendered in the recipient's open/focused conversation
  view" is knowledge only the client has. The client sends a **STOMP `SEND`** frame to the
  application destination **`/app/message.read`** (static destination — roomId travels in the
  body, so no `@MessageMapping` template var / dot-`PathMatcher` config is needed) with body:
  ```
  { "roomId": "<uuid>", "lastReadMessageId": "<uuid>" }
  ```
  ws-gateway's `@MessageMapping("/message.read")` handler resolves the authenticated
  `recipientId` from the STOMP session principal (never the body), **verifies the sender is a
  member of `roomId`** (same check as §3, cached), and publishes
  `MessageReadEvent(lastReadMessageId, roomId, recipientId, now, now)` with key `receipt.read`.
  No REST call, no DB access on the socket path.

#### 3. Subscribe-time authorization — one-time internal membership check (option a)
A client may only SUBSCRIBE to `/topic/rooms.{roomId}` for a room it is a member of (same rule
REST enforces for post/read). ws-gateway does **not** own or query the `room_members` table.
It enforces membership with a **one-time internal REST call to chat-service at SUBSCRIBE time**
(and on the `/app/message.read` SEND) — **not** per message, so the coupling is limited to
infrequent subscribe/read events, not the delivery hot path.

- **Mechanism.** A Spring `ChannelInterceptor` on ws-gateway's **inbound** channel intercepts
  `StompCommand.SUBSCRIBE` frames whose destination matches `/topic/rooms.{roomId}`, extracts
  `roomId`, and calls chat-service before the subscription is admitted to the simple broker.
  On 200 → allow; on anything else → reject the frame with a STOMP `ERROR` (client shows "no
  access" / retries after refresh).
- **Endpoint (new, cheap — add to chat-service under CHAT-107).**
  `GET /rooms/{roomId}/members/me` →
  - `200` `{ "roomId": "...", "userId": "...", "role": "OWNER"|"MEMBER" }` if the caller is a
    member;
  - `404` if the caller is not a member **or** the room does not exist (same response, to avoid
    room-existence enumeration);
  - `401` if the token is invalid/expired.
  It reuses chat-service's existing `RoomAccessService.requireMembership` logic that already
  backs POST/GET, and is deliberately lighter than CHAT-34's `GET /rooms/{roomId}/members`
  (no member-list fanout — this is a boolean-shaped self-check).
- **Auth on the internal call.** ws-gateway forwards the **user's own access token** (captured
  at CONNECT) as `Authorization: Bearer <token>` on the call; chat-service validates it as a
  normal resource-server request and derives the membership subject from the token `sub`. No
  service account / confidential client is introduced (consistent with the Sprint-1 "no
  confidential client" decision). If the captured token has expired, the call returns 401 and
  ws-gateway rejects the SUBSCRIBE; the client reconnects with a fresh token. (Refreshing the
  token over a live socket is a post-Sprint-1 hardening, noted, not built here.)
- **Caching (what the cache does — and does not — bound).** ws-gateway caches a positive
  `(sessionId, roomId)` result in-memory with a short TTL (~60 s) to avoid re-hitting
  chat-service on reconnect/re-SUBSCRIBE storms and on each `/app/message.read`. **This cache
  only short-circuits *future client-initiated* SUBSCRIBE / `message.read` calls; it does
  nothing to an already-admitted subscription.** Once a SUBSCRIBE is admitted to the pod's
  simple-broker subscription registry, `RoomEventBroadcastListener` keeps delivering to it for
  the life of the STOMP session regardless of the cache — the TTL expiring is a passive
  cache-miss on the *next* client call, not a re-check of live subscriptions. Revocation of an
  already-open subscription is therefore **not** handled by the cache TTL; it is handled by the
  explicit membership-revoked event in §4 below. (Historical note: an earlier version of this
  section claimed "a user who left a room keeps delivery for at most the TTL (~60 s)" — that was
  **factually wrong** against the implementation and is corrected by §4; a left user's live
  subscription was in fact never re-checked and kept delivering until disconnect. Bug fixed
  under CHAT-37.)
- **`/app/message.read` reuses the same check** before publishing a `MessageReadEvent`, so a
  non-member cannot forge read receipts.

#### 4. Membership revocation of a live subscription — `RoomMembershipRevokedEvent` (CHAT-37)
The subscribe-time check in §3 admits a subscription **once**; nothing re-checks it afterwards,
so a user who leaves a room (or, once that endpoint exists, is removed by the owner) keeps
receiving every message on their still-open `/topic/rooms.{roomId}` subscription until they
disconnect. That is the CHAT-37 authorization-staleness bug.

**Decision — event-driven revocation (chosen over periodic re-check).** When a membership ends,
chat-service publishes a `RoomMembershipRevokedEvent`; ws-gateway consumes it and force-drops
that user's live subscription to the room. Chosen because:
- It is **O(actual leave/remove events)**, which are infrequent, rather than the O(live
  subscriptions × time) continuous polling load that a periodic re-check (or a TTL-expiry-driven
  re-verify) would put on chat-service — the wrong cost curve to add for a rare event, and it
  grows with the connection count we are explicitly trying to scale.
- It is **architecturally consistent**: it reuses the exact persist-then-publish path chat-service
  already uses for `RoomMessageCreatedEvent`/`RoomMessageStatusChangedEvent`, the same
  `chattera.events.room-membership-revoked.<roomId>` topic-naming family, the same wildcard
  `chattera.events.*` subscription on ws-gateway, and the same `@KafkaHandler`-by-type dispatch
  — **no new topic or consumer configuration required**.
- It revokes in **broker-latency time (sub-second)** rather than up to ~60 s.

**New shared event (add to `common-domain`, a record implementing `DomainEvent`, mirroring
`MessageDeliveredEvent`):**

```
// Published by chat-service after a user's membership in a room ends (self-leave now;
// owner-removal when that endpoint is added). Consumed by ws-gateway, which force-drops
// that user's live subscription to /topic/rooms.{roomId}.
// Topic: chattera.events.room-membership-revoked.<roomId> — same naming family as
// RoomMessageCreatedEvent, delivered via ws-gateway's existing chattera.events.* wildcard
// subscription; every pod receives it, and only the pod(s) holding that user's socket act
// on it.
RoomMembershipRevokedEvent(UUID roomId, String userId, Instant occurredAt)
```

**Publish side (chat-service).** In `RoomService.leaveRoom`, after `roomMemberRepository.delete(
membership)` and still inside the `@Transactional` method, publish a Spring application event:
`applicationEventPublisher.publishEvent(new RoomMembershipRevokedEvent(roomId, userId, Instant.now()))`
(inject `ApplicationEventPublisher` exactly as `MessageService` does). Add a
`@TransactionalEventListener(phase = AFTER_COMMIT)` handler to `ChatEventListener` that calls
`eventPublisher.publish("room-membership-revoked." + event.roomId(), event)`. AFTER_COMMIT is required so a
rolled-back leave never emits a phantom revoke — identical to the message/receipt path. Notes:
- The OWNER-leave auto-transfer promotes a new owner, but only the **leaver** is revoked (single
  `userId`); the promoted owner keeps their subscription. Correct as-is.
- A future owner-remove-member endpoint MUST publish the same event for the removed `userId`.

**Consume side (ws-gateway).** Add a `@KafkaHandler` to `RoomEventBroadcastListener` for
`RoomMembershipRevokedEvent`. Unlike the message/status handlers it **must NOT `convertAndSend`
the event to `/topic/rooms.{roomId}`** (that would fan a revoke out to every subscriber); it
targets only the revoked user's own subscription(s):
1. `simpUserRegistry.findSubscriptions(...)` filtered to subscriptions whose destination equals
   `/topic/rooms.{roomId}` **and** whose `getSession().getUser().getName()` equals `event.userId()`.
2. For each match, **force-unsubscribe** exactly that `(sessionId, subscriptionId)` by sending a
   synthetic STOMP `UNSUBSCRIBE` onto the injected `clientInboundChannel`
   (`@Qualifier("clientInboundChannel") MessageChannel`):
   ```
   StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
   a.setSessionId(subscription.getSession().getId());
   a.setSubscriptionId(subscription.getId());
   a.setLeaveMutable(true);
   clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], a.getMessageHeaders()));
   ```
   The simple broker processes this identically to a client-sent UNSUBSCRIBE, removing just that
   one subscription and leaving the socket and the user's other room subscriptions intact.
   `StompAuthChannelInterceptor` only acts on CONNECT/SUBSCRIBE, so the synthetic UNSUBSCRIBE
   passes through untouched.
3. **Evict the stale positive cache entry** for that `(sessionId, roomId)` — add
   `RoomMembershipChecker.evict(String sessionId, UUID roomId)` (removing the single cache key,
   distinct from the existing session-wide `evictSession`). This is **mandatory**: without it a
   just-revoked client that re-SUBSCRIBEs within the ~60 s TTL would be re-admitted from the
   stale positive cache instead of re-hitting chat-service (which now returns 404).

Pods that do not hold the user's socket find no matching subscription and no-op — the broadcast
is safe to fan to all pods.

**Residual staleness bound (honest).** Under normal operation revocation is bounded by
broker+handler latency (sub-second). The one remaining gap: publish is best-effort and never
throws (the `EventPublisher` contract), and the event is not retried, so during a broker outage a
left user's subscription could keep delivering until their socket next reconnects (at which point
the room is gone from their REST room-list and is not re-SUBSCRIBEd). This is consistent with the
existing "bus is best-effort, Postgres is source of truth" posture and is closed by the same
deferred transactional-outbox hardening already noted for the producer path — acceptable for
Sprint 1, recorded not hidden.

**Alternative mechanism (fallback, if the targeted UNSUBSCRIBE proves fiddly).** Closing the
user's whole WebSocket session also revokes correctly and self-heals via the existing lossless
reconnect (client re-fetches its room list — now without the left room — and re-SUBSCRIBEs), and
reuses `WebSocketSessionLifecycleListener`'s existing cleanup. It is blunter (drops the user's
*other* room subscriptions too, forcing a full reconnect for a single-room leave), so the targeted
UNSUBSCRIBE above is preferred; socket-close is an acceptable fallback, not the default.

## Sprint 1 Architecture Focus
- define API contracts
- define authentication flow
- create baseline deployment and observability setup
- establish integration points between chat and file services

## Scalability & High-Availability Architecture (CHAT-24)
This is the real scalability/HA design for the system as actually built (Java 21 / Spring
Boot 3.5.x; gateway, ws-gateway, profile-service, chat-service, file-service; single-instance
PostgreSQL / Redis / Kafka / Keycloak; docker-compose only, nothing deployed to any cloud).
It **supersedes** the earlier "Known limits / scaling considerations" placeholder that lived
here (that section recorded the problems; this section decides them).

**Scope of this pass.** Target NFR: up to 1,000,000 registered users with low-latency
delivery and high availability (FR-05, NFRs). It fixes the topology and the mechanisms and is
meant to be buildable by developer/devops-engineer, not a survey of options. **Multi-region is
explicitly still deferred** (see §8); the availability target for this pass is
**single-region, multi-AZ, no single point of failure**.

**Reconciled against performance-engineer's CHAT-110 targets (2026-07-27).** The concrete
numbers that were pending when this section was first written have landed and have been folded
into the tunables below (they are no longer placeholders). The targets used throughout this
section:
- **Concurrency:** ~50,000 peak concurrent active users; **~60,000 peak concurrent WebSocket
  connections** (multi-device factor).
- **Message rate:** ~**400 msg/sec sustained** system-wide, **1,500–2,000 msg/sec burst**.
  Fanout mix 60% DM (fanout=1) / 40% room (avg fanout ~6) → ~**1,200 WS push events/sec
  sustained, 3,000–6,000 burst** (post-fanout, delivered locally per pod).
- **REST:** ~**1,400–1,800 req/sec sustained**, ~**3,000 req/sec burst**.
- **Latency:** end-to-end send-to-delivery **p95 ≤ 500 ms, p99 ≤ 1000 ms** (once CHAT-107 exists).
- **Availability:** **99.9%** (99.95% is a flagged stretch, *not* a Sprint-1 commitment);
  **RPO ≤ 5 s** on Postgres (the real durability guarantee); **RTO ≤ 5 min** for AZ-level
  DB/broker failover.
- **Keycloak:** ~**180–200 token-refresh req/sec** sustained at peak (driven by the 5-min
  access-token TTL).

**Net effect of the reconciliation:** the topology and every mechanism hold unchanged — the
numbers land comfortably inside the design's headroom. **One material change:** the `messages`
partition interval moves from "monthly" to **daily** (§2), because 400 msg/sec sustained makes
a monthly partition a ~1B-row object, which defeats partitioning. Everything else is a tunable
now filled with a real value (HPA thresholds, per-pod socket ceiling, pool sizes, replica
counts, the §3 Kafka trigger). **Two risks surfaced by CHAT-110 are recorded, not silently
absorbed:** unbounded room membership (§3, mega-room fanout) and default HikariCP pool sizing
(§2.2).

**Runtime platform (decided for this pass).** Container orchestration on **Kubernetes**
(managed — GKE, matching the notional GCP target; kept cloud-portable). Every Chattera
service is already a stateless Spring Boot container, so each becomes a k8s `Deployment` with
a `HorizontalPodAutoscaler`. devops-engineer owns the actual cluster/IaC and the CHAT-108
pipeline that builds/ships these images; this section owns the *shape*.

### 0. Topology at a glance
```
                         (managed, multi-AZ, HA cloud LBs)
  clients ──HTTPS──> [L7 LB: api.chattera]  ──> gateway Deployment (N pods, HPA)
          ──WSS───> [L7 LB: ws.chattera, WS-upgrade + long idle timeout]
                                              └─> ws-gateway Deployment (M pods, HPA on conns)
          ──HTTPS──> [L7 LB: auth.chattera]  ──> Keycloak StatefulSet (3 nodes, Infinispan)

  gateway ─routes─> profile-service / chat-service / file-service (each Deployment + HPA)

  writes/reads ─> PgBouncer ─> PostgreSQL (per-service cluster: primary + replica(s), multi-AZ)
  presence/cache ─> Redis (managed HA / Cluster, multi-AZ)
  events ─> Kafka (3-broker cluster, multi-AZ, replication factor 3) ── every ws-gateway pod
            wildcard-subscribes to the shared chattera.events.* namespace (CHAT-114 tracks
            the topic-count/consumer-group fixes needed for this to fan out correctly)
  files ─> cloud object storage (GCS/S3) via presigned URLs (MinIO is dev-only)
```

### 1. Compute scaling & load balancing
**Load balancers (new — none exist today).** Three managed L7 load balancers, preserving the
REST/WebSocket separation that is already an architecture principle:
- `api.chattera` → **gateway** (REST). L7, least-request, path/host routing to the backing
  services. This is also where edge rate-limiting/TLS terminate.
- `ws.chattera` → **ws-gateway** (WebSocket). Must permit HTTP `Upgrade` and carry **long idle
  timeouts** (sockets live for hours) and **connection draining** on pod removal. Balancing is
  **least-connection**, not round-robin — long-lived connections make round-robin pile onto
  whichever pod came up first.
- `auth.chattera` → **Keycloak** (§4).

**The stateless services (gateway, profile, chat, file) scale by just adding pods.** This is
the payoff of decisions already made and it needs **no change**: JWT validation is local
against the cached realm JWKS (no per-request Keycloak call, §"Token validation"), there is no
server-side HTTP session, and the `azp` check is per-instance and offline. So horizontal scale
is purely: `Deployment.replicas` + `HorizontalPodAutoscaler`. HPA signal is CPU + p95 latency
(request-bound services). **Tuned to CHAT-110:** target **CPU ~65%** and scale to hold the p95
latency budget (§Reconciled: p95 ≤ 500 ms end-to-end, of which the REST tier owns a fraction).
Sizing anchor is **~1,400–1,800 req/sec sustained, ~3,000 req/sec burst** across gateway +
backing services; run a floor of **≥3 pods per Deployment spread across 3 AZs** (so a single-AZ
loss still leaves ≥2), and let HPA add pods toward the burst target. These are request-bound
and CPU-light per request (local JWT validation, no per-request Keycloak call), so the floor is
driven by AZ-redundancy, not throughput.

**Health/readiness (new, required for LB + k8s).** Enable Spring Boot's liveness/readiness
probe groups and wire them:
- `livenessState` → k8s `livenessProbe` (restart a wedged pod).
- `readinessState` → k8s `readinessProbe` **and** LB health check. Readiness must go `DOWN`
  when a required downstream (DB for profile/chat, broker for ws-gateway) is unreachable, so
  the LB stops routing to a pod that can't serve — Spring Boot health *groups* express this.
- Set `server.shutdown=graceful` + a termination grace period so in-flight requests drain on
  scale-in/rollout. This is a config addition per service, not a redesign.

**ws-gateway needs its own treatment** because it holds live WebSocket connections — the one
piece of transient per-instance state in the system. The design already established for
CHAT-107 holds and is what makes this tractable; scaling it is mostly *operational*:
- **No sticky sessions.** A socket is transient, not authoritative (history→Postgres,
  presence→Redis, routing→Kafka, identity→JWT). A client can (re)connect to **any** pod,
  re-CONNECT / re-SUBSCRIBE / re-fetch recent history, and lose nothing. **This does not
  change at scale** and is the property that makes ws-gateway horizontally scalable and
  drain-safe.
- **Scale on connection count, not CPU.** Idle sockets are cheap on CPU but bound by memory /
  file descriptors. HPA (or KEDA) drives off an exported **active-connection-count** metric
  with a per-pod ceiling. **Tuned to CHAT-110** (~60,000 peak concurrent WS connections): set a
  **per-pod ceiling of ~20,000 sockets** (conservative — idle Netty/STOMP sockets run
  ~50–100 KB heap each, so ~20k ≈ 1–2 GB; requires the pod's `ulimit -n` raised to ~65k, well
  above k8s defaults). HPA target **~70% of the ceiling (~14,000 sockets/pod)** → at 60k peak
  that is ~**5 pods** actively serving; run a **floor of ≥6 pods across 3 AZs** so a single-AZ
  loss (down to ~4 pods ≈ 15k/pod) still sits under the ceiling. The 20k figure is deliberately
  conservative and stays a load-test tunable — raise it if soak tests show headroom.
- **Drain = disconnect-with-reconnect.** On scale-in/rollout, a pod stops accepting new
  sockets, sends a close/`reconnect` directive to its clients, and lets the LB drain; clients
  reconnect elsewhere losslessly (same reconnect path as a crash). Safe precisely because of
  persist-first + Postgres-as-source-of-truth.
- **Rebalancing after scale-out.** New pods start empty; existing sockets don't migrate. A
  bounded **max-connection-age** (client reconnects periodically) plus least-connection LB
  spreads load onto new pods over time. Tunable; note it, don't over-engineer it.

### 2. Database scaling (supersedes single-instance / schema-per-service co-location)
Today: one PostgreSQL instance, profile-service and chat-service on **separate schemas within
it** (CHAT-31). Three changes, in escalation order:

1. **Split co-located schemas into an instance-per-service.** profile and chat get **separate
   PostgreSQL clusters**. Their volume and scaling profiles diverge hard (profiles: ~1M rows,
   read-mostly, tiny; messages: unbounded append-heavy growth), and co-location couples their
   failure and scaling domains. This is cheap because the **schema-per-service boundary was
   deliberately kept clean** — no cross-schema joins or FKs — so the split is a
   connection-string/Flyway-target change, **not a code change**. (Keycloak's DB is already
   separate.) The *boundary* is unchanged; only the *co-location* ends.
2. **Connection pooling with PgBouncer (transaction mode) in front of each cluster.** At
   `services × pods × Hikari-pool-size`, raw Postgres backends (heavyweight, practical ceiling
   a few hundred) are exhausted long before the DB is actually busy. PgBouncer in
   **transaction pooling** multiplexes many client connections onto few server connections.
   **Developer caveat:** transaction-mode pooling forbids session-scoped state — disable
   Hibernate server-side prepared-statement caching (or run PgBouncer ≥1.21 with
   prepared-statement support) and avoid session-level advisory locks / `SET`. HikariCP stays
   as the in-process pool pointed at PgBouncer. PgBouncer itself must be HA (run as a pooled
   Deployment, or use the managed offering's built-in pooler).

   **Pool sizing — explicit (CHAT-110 flag: no HikariCP overrides exist anywhere; everything is
   on Spring Boot's default `maximum-pool-size=10`).** Left as-is that is a latent ceiling:
   even without PgBouncer, `services × pods × 10` blows past a managed-Postgres backend limit
   (~100–200) — e.g. chat-service alone at its floor of 3 pods = 30 backends, and each replica
   read path adds more. The PgBouncer transaction-pooling tier is exactly what decouples app
   pool size from backend count, so the guidance is:
   - **Keep each service's HikariCP `maximum-pool-size` small and explicit — target ~10** (the
     default is fine *behind* PgBouncer, but pin it rather than inherit it, and set a matching
     `minimum-idle` and a sane `connection-timeout`). This is the count of *client* connections
     a pod holds to PgBouncer, which are cheap.
   - **PgBouncer `default_pool_size` (server-side, per database) is the real budget** — size it
     so total server backends across all poolers stay within the Postgres `max_connections`
     ceiling with headroom for admin/replication/failover (e.g. **~50–100 server connections
     per cluster** against a 200-connection Postgres, leaving room). With transaction pooling,
     a pool of ~50 server connections comfortably fronts hundreds of app-side client
     connections at the ~1,400–1,800 req/sec target because connections are held only for the
     duration of a transaction, not a request.
   - **Action for developer:** add the explicit `spring.datasource.hikari.*` block per service
     (currently absent) so the pool is a declared, reviewable number rather than a framework
     default — tracked as its own ticket under the CHAT-24 epic.
3. **Read replicas for the read-heavy paths.** GET history and profile reads dominate over
   writes. Add streaming replica(s) per cluster and route `@Transactional(readOnly = true)`
   traffic to them (a routing `DataSource` / read-write split). **Sized to CHAT-110:** the read
   skew and volume differ per cluster, so — **chat cluster: primary + 2 replicas** (read-heavy:
   GET-history dominates, and replicas also serve HA failover); **profile cluster: primary + 1
   replica** (read-mostly but tiny ~1M rows — one replica covers both read offload and HA). Both
   start here and scale replica count off observed read-IOPS, not a formula. **Caveat: replication lag** —
   a sender must not fail to see their own just-posted message. Read-your-writes is already
   satisfied by two existing properties: the sender's own real-time echo comes over the socket
   (not a replica read), and the *most-recent* history page can be served from the primary
   while *older* keyset pages (which are immutable) come from replicas. Wire the split with
   that rule; it is not a new subsystem.

**`messages` table partitioning — the first thing flagged, now decided (interval set by
CHAT-110).** Convert `messages` to a **declaratively RANGE-partitioned table by `created_at`**.
Rationale: message traffic is append-heavy and time-ordered, the hot working set is recent
messages, the existing keyset index `(room_id, created_at DESC, id DESC)` is already
time-aligned, and every history query is time-bounded via the cursor — so range-by-time gives
partition pruning on reads, smaller per-partition indexes, and trivial archival/drop of cold
partitions. Per-room locality is served by the composite index **within** each partition; we do
**not** need HASH-by-`room_id` for that.

**Interval = daily (changed from "monthly" after reconciliation).** performance-engineer's
**~400 msg/sec sustained** works out to ~**35M rows/day, ~240M/week, ~1B/month**. The originally
sketched *monthly* interval would therefore produce **~1-billion-row partitions**, whose indexes
and vacuum/maintenance cost defeat the entire point of partitioning. **Daily partitions
(~10–35M rows each) sit squarely in the healthy range.** Automate create/pre-provision/retention
with **pg_partman** (daily granularity means hundreds of partitions/year — this must not be
manual). Fall back to weekly only if observed sustained rate proves materially below the 400/sec
target. Do this migration **early** (chat-service was just implemented and holds little data —
converting a small table is a routine Flyway change; a huge one is not).

**Beyond one primary (escalation, not built now).** If a single chat primary's *write*
throughput becomes the ceiling even after partitioning, the next tier is **sharding `messages`
across multiple chat Postgres clusters by `hash(room_id)`** (a room's history stays whole on
one shard; the app routes by room). This is a genuinely larger change and is **deferred with a
trigger** (write-IOPS on the chat primary as the observed ceiling), not designed here.

### 3. Messaging backbone scaling — Kafka topic/consumer-group topology (gap tracked as CHAT-114)
The event backbone is Kafka (see "Event bus decision — Kafka" above); the soft ceiling this
section originally protected against — RabbitMQ queue/binding count exploding to one binding
per live room subscription under a naive STOMP-broker-relay topology — no longer applies to
the broker itself. The same **shape** of problem re-appears one layer down, in how chat-service
and ws-gateway actually use Kafka today, and is tracked as its own backlog ticket (CHAT-114)
rather than fixed in this pass.

**Why the obvious mitigations don't apply here.** You cannot partition ws-gateway by room
(consistent-hash routing, room→instance affinity, cellular-by-room): a single user holds **one
socket** but subscribes to **many** rooms, so that user's pod must be able to receive events
for *any* of their rooms. Given non-sticky, one-socket-per-user, **every ws-gateway pod must be
able to receive any room's event.** That rules out room-sharding the delivery tier at this
tier and points to a single answer: every pod consumes the full event stream and filters
locally against its own socket registry.

**What is actually built today.** `KafkaEventPublisher` (via `ChatteraMessagingProperties`)
publishes each event to a distinct, broker-auto-created topic per room per event type — e.g.
`chattera.events.room-message-created.<roomId>`, `chattera.events.room-message-status-
changed.<roomId>`, `chattera.events.room-membership-revoked.<roomId>` — with no partition key
set. Producer (`ChatEventListener`) and consumers (`RoomEventBroadcastListener`,
`ReceiptEventListener`) all go through the `EventPublisher` abstraction, which is exactly the
"contained, not a rewrite" payoff this section originally banked on: the write side and the
abstraction seam don't move regardless of what's decided below. Consumers wildcard-subscribe
with `@KafkaListener(topicPattern = "chattera.events.*")` and dispatch by payload type via
`@KafkaHandler` — the Kafka-native analogue of the app-side hashmap filtering the earlier
RabbitMQ broadcast-queue design used.

**Where this diverges from the "every pod sees every event, O(pods) not O(rooms)" goal:**
- **Topic count is O(rooms × event types), not O(pods).** The RabbitMQ design's entire point
  was capping broker object count at the pod count regardless of room count. The current
  per-room-topic scheme inverts that: at CHAT-110's ~7,000 concurrently-active rooms this is
  already tens of thousands of topics, and Kafka clusters have a practical ceiling on total
  topic/partition count (typically low thousands per broker) long before 1,000,000 users' worth
  of rooms exist. This is the same failure mode the earlier binding-count analysis was written
  to avoid, now reappearing on the Kafka side.
- **The shared `ws-gateway` consumer group id breaks the fan-out guarantee at more than one
  pod.** Kafka consumer groups exist to *split* partitions across members, not to broadcast the
  same message to every member. Every ws-gateway pod is currently registered under the same
  group id (`ws-gateway`, see `application.yml`), so scaling past one pod does not reproduce the
  "every pod receives every event" property this whole section depends on — it does the
  opposite. At Sprint 1's single-pod scale this is invisible; it becomes a correctness bug (some
  pods silently miss events for rooms they hold sockets for) the moment ws-gateway runs with
  `replicas > 1`, which CHAT-24 §1 requires (floor of ≥6 pods).

**Target design (tracked as CHAT-114, not built in this pass).** A small, fixed set of Kafka
topics — one per event *type*, not per room — partitioned by `roomId`/`userId` as the Kafka
message key, combined with a fan-out-safe consumer strategy per pod (a unique consumer group id
per ws-gateway pod, or Kafka's manual partition-assignment API, so every pod's consumer receives
every partition rather than one slice of them). That reproduces the RabbitMQ broadcast-queue
property — topic/partition count bounded by event-type count, not room count, and consume load
per pod equal to the full publish rate — without reintroducing per-room broker objects.
`DomainEvent` gaining a `partitionKey()` method (→ `roomId`) is the concrete first step.

**Validated against CHAT-110.** Once on a bounded topic set, each pod's consume rate is the same
**~400 events/sec sustained, ~2,000/sec burst** figure this section already sized for, which a
single Kafka consumer handles trivially (one to two orders of magnitude below what a single
consumer sustains). The ~1,200 sustained / 3,000–6,000 burst *WS push* events/sec figure remains
the **post-fanout** number, spread across all pods' local sockets — it does not land on any
single pod's consume path. The ceiling this section actually cares about is topic/partition
count and per-pod fan-out correctness (CHAT-114 above), not raw throughput.

**Design-time risk — unbounded room membership (CHAT-110 flag 2, recorded not mitigated).**
CHAT-104 shipped with **no cap on room membership**, and every fanout number here assumes the
CHAT-110 average room size of ~6–8. A single viral/uncapped room breaks that by orders of
magnitude: one `RoomMessageCreated` for a 50,000-member room becomes a **50,000-way push burst**
from a *single* consumed event. **Whether to cap membership is a product decision (business-
analyst / PM), not the architect's to set** — but its impact on *this* design is on record:
- **What the topology does well:** the per-pod local-filtering design already **distributes**
  mega-room fanout the right way — each pod pushes only to the mega-room sockets it locally
  holds, so the 50k pushes are spread across the whole ws-gateway fleet rather than concentrated
  on one pod. The broker/consume side is unaffected (still one event per message). So the
  failure is **not** a broker or topic/partition failure.
- **Where it concretely gets worse and there is no guard today:** the per-pod **outbound** path
  has **no backpressure or circuit-breaker**. A mega-room message spike can saturate a pod's
  socket-write capacity and inflate p95 delivery latency past the 500 ms budget for *all* users
  on that pod, not just the mega-room's. Today it simply degrades.
- **Mitigation direction (cheap, enabled by persist-first — flag for a follow-up ticket, not
  built in this pass):** give each socket a **bounded outbound queue**; on overflow, **drop the
  push and mark the socket stale** rather than blocking the shared consumer — the client
  recovers losslessly via the existing reconnect + history-refetch path (Postgres is source of
  truth). That converts an unbounded-fanout meltdown into bounded, self-healing degradation. It
  is the same property that makes drain/reconnect safe (§1), reused as backpressure. **Until a
  membership cap and/or this bounded-queue guard exist, an uncapped mega-room is an accepted,
  documented failure mode of this design, not a handled one.**

**Broker HA:** Kafka as a **3-broker cluster, multi-AZ**, replication factor **3** with
`min.insync.replicas=2` for topics that need durability guarantees. The per-room-per-event-type
topics from today's implementation are individually cheap/uncritical (Postgres remains the
source of truth for durable state), but their *count* is exactly the CHAT-114 concern above —
a topology problem, not a durability one.

### 4. Keycloak scaling
Keycloak load is driven by **login/token-issuance/refresh rate**, not request rate — Chattera
services validate tokens **locally** against cached JWKS and never call Keycloak per request, so
Keycloak scaling is primarily an **availability** concern, secondarily throughput.
- **3-node Keycloak cluster, multi-AZ**, behind `auth.chattera`. Keycloak 26 uses **Infinispan**
  distributed caches for sessions/auth-codes; in k8s use JGroups **KUBE_PING/DNS_PING** discovery
  so login-flow state (auth code, SSO session) is replicated — **no LB stickiness required**.
- **Validated against CHAT-110 (flag 4): the ~180–200 token-refresh req/sec sustained peak is
  comfortably absorbed.** A refresh grant is a signed-token + Infinispan session lookup; a single
  Keycloak node sustains that order of load, and 3 nodes give headroom plus the AZ redundancy
  that is the real reason for the count. No sizing change — but note the **direct lever:** refresh
  rate is inversely proportional to access-token TTL (the realm's current **5-min** TTL is what
  produces ~200/sec). If refresh load ever becomes the Keycloak ceiling, **raising the
  access-token TTL** cuts it proportionally, traded against slower revocation propagation — a
  config knob, not a topology change. Sized so refresh throughput is **not** the constraint here;
  availability is.
- **HA Postgres backend** for Keycloak (its own cluster, same primary+replica+failover story as
  §2; already a separate DB today).
- **Service-side resilience to Keycloak blips:** services cache JWKS and validate offline, so a
  brief Keycloak outage does not stop request validation. Ensure services **re-fetch JWKS on an
  unknown `kid`** so Keycloak signing-key rotation is transparent. This is existing resource-
  server behavior to confirm, not new code.

### 5. Redis scaling
Redis holds `presence:{userId}` keys and the hot-conversation cache — all **single-key**
operations (GET/SET/EXPIRE), no cross-user multi-key transactions, so the keyspace **shards
cleanly**.
- **HA now:** **managed Redis with automatic failover** (Cloud Memorystore / equivalent), or
  self-run **Redis Sentinel** (primary + replicas + auto-failover), multi-AZ. Managed is
  recommended for ops load.
- **Scale-out when a single primary is the memory/throughput ceiling:** **Redis Cluster**
  (hash-slot sharding) keyed by `userId`/`roomId`. The single-key access pattern means no
  cross-slot operations — a clean fit.
- **Separate presence from cache eviction policy.** Cache is LRU-evictable (`allkeys-lru`);
  presence must **not** be arbitrarily evicted — it relies on TTL and heartbeat refresh. Keep
  them in separate logical Redis (separate cluster or at least separate instance/policy).
- **Failover is low-risk for presence:** a lost `presence:*` key is self-healing — ws-gateway
  re-writes it on the next heartbeat/reconnect and every key carries a TTL. Cache loss just
  repopulates from Postgres.

### 6. High availability — no single point of failure (single region, multi-AZ)
**SLO targets (CHAT-110):** **99.9% uptime** for this pass (99.95% is a flagged *stretch*, not a
Sprint-1 commitment). Data durability: **RPO ≤ 5 s** — the Postgres primaries must run
**synchronous or low-lag streaming replication** so an AZ-level primary loss loses ≤5 s of
committed writes (this is the real durability guarantee; the transient broker/cache tiers are
recovered by reconnect+refetch, not by RPO). **RTO ≤ 5 min** for AZ-level DB/broker failover —
achievable with managed-HA/Patroni automatic promotion + PgBouncer reconnect; the per-component
table below meets it. These bound the mechanisms, not the other way around: e.g. RPO ≤ 5 s is
why replica lag on the *write* path matters and why the primary, not a lagging replica, serves
read-your-writes (§2.3).


| Component | HA mechanism | Failure behavior |
|---|---|---|
| gateway / profile / chat / file | k8s Deployment ≥2 pods, pod anti-affinity across ≥3 AZs, HPA | LB health-check drops dead pods; k8s reschedules |
| ws-gateway | Deployment ≥2 pods multi-AZ; non-sticky reconnect | client reconnects to any surviving pod, refetches history — lossless |
| Load balancers | managed cloud L7 LBs | inherently multi-AZ, no action |
| PostgreSQL (per service) | primary + replica(s) multi-AZ, automatic failover (managed HA / Patroni) | promote replica; PgBouncer reconnects |
| PgBouncer | pooled Deployment / managed pooler | multiple instances, no single pooler |
| Kafka | 3-broker cluster multi-AZ, replication factor 3, `min.insync.replicas=2` | partition leader re-election on broker loss; producers/consumers reconnect automatically |
| Keycloak | 3 nodes, replicated Infinispan, multi-AZ, HA DB | any node serves; sessions replicated |
| Redis | managed HA / Sentinel / Cluster + replicas, multi-AZ | auto-failover; presence self-heals via TTL+heartbeat |
| Object storage | cloud object storage (GCS/S3), inherently multi-AZ durable | MinIO is **dev-only**; prod swaps to the managed store |

### 7. What holds unchanged vs. what needs rework
**Holds unchanged at scale (Sprint 1 deliberately built for this — the "contained, not a
rewrite" thesis proven out):**
- **Stateless services + local JWT/JWKS validation** → horizontal scale is "add pods," with
  **no** session store to introduce. The single biggest win.
- **Postgres as source of truth / bus as transient delivery** → lets ws-gateway queues be
  transient, enables replica reads, and makes reconnect-and-refetch lossless.
- **`EventPublisher` abstraction** → producers don't change when the consumer topology changes
  (§3) or if Kafka is later adopted.
- **JWT `azp` validation pattern** → per-instance, offline, scales freely.
- **Non-sticky ws-gateway reconnect + history refetch** → the property that makes the delivery
  edge horizontally scalable and drain-safe.
- **Schema-per-service boundary** → clean boundary is exactly what makes the instance-per-
  service DB split (§2) a config change.
- **Keyset pagination on the composite index** → correct and cheap per-partition, unchanged.
- **MinIO presigned-URL pattern (file bytes bypass the app tier)** → unchanged; only the
  backing store swaps MinIO→cloud object storage.

**Needs rework / net-new:**
- **ws-gateway consumer topology:** STOMP-relay-per-subscription → **Kafka wildcard
  subscription + app-side room→session filtering**, with the topic-count and consumer-group
  fixes tracked under CHAT-114 (§3). Contained to ws-gateway consumer wiring (CHAT-107).
- **PostgreSQL:** single instance → **instance-per-service + PgBouncer + read replicas +
  RANGE-partitioned `messages`** (§2).
- **Kafka / Redis / Keycloak:** single instance → **clustered/HA** (§3/§5/§4).
- **Net-new platform:** managed **load balancers**, **Kubernetes** orchestration, **HPA/KEDA**,
  **liveness/readiness probes + graceful shutdown**, autoscaling metrics/exporters, and the
  MinIO→cloud-object-storage swap. None of these exist today.

### 8. Explicitly still deferred (with triggers)
- **Multi-region / active-active / DR.** Out of scope for this pass; target is single-region
  multi-AZ HA. Multi-region adds cross-region Postgres replication, Keycloak cross-site
  Infinispan, Redis/Kafka cross-region replication (e.g. MirrorMaker 2), object-storage
  geo-replication, and a global/DNS LB — a separate epic. Trigger: a latency-for-distant-users
  or regional-DR requirement from product.
- **ws-gateway cellular sharding by room.** Only relevant if per-pod consume throughput becomes
  the ceiling even after the CHAT-114 topic/consumer-group fix. Genuinely complex (a user spans
  rooms across cells). Deferred with the throughput trigger.
- **Sharding `messages` across multiple chat DB clusters by `hash(room_id)`** (§2). Deferred
  with the chat-primary write-IOPS trigger.
- ~~**Exact capacity numbers** (replica counts, per-pod socket ceiling, partition interval, HPA
  thresholds, node counts).~~ **Landed and reconciled** against performance-engineer's CHAT-110
  targets (2026-07-27) — folded into §§1–5 as concrete values (per-pod socket ceiling ~20k;
  HPA CPU ~65% / ws-conn ~70%; chat +2 / profile +1 replicas; daily `messages` partitions;
  Hikari ~10 behind PgBouncer ~50–100 server conns). No longer deferred. Remaining true
  unknowns are **load-test-confirmed** ceilings (validate the ~20k socket figure under soak,
  and the CHAT-114 topic/consumer-group fix once built), not undesigned decisions.

### 9. Delivery / handoffs
This is buildable now. Suggested breakdown for scrum-master to ticket under the CHAT-24 epic
(sequence roughly: platform first, then per-component HA, then the topology change):
- **devops-engineer:** Kubernetes cluster + IaC; the three L7 LBs (REST/WS/auth) with WS-upgrade
  + draining on `ws.chattera`; managed HA PostgreSQL (per service) + PgBouncer; Kafka 3-broker
  cluster; managed HA Redis; Keycloak 3-node Infinispan cluster; MinIO→cloud object storage;
  probe/metric scrape wiring. Depends on CHAT-108 (pipeline) landing first.
- **developer:** liveness/readiness health groups + `server.shutdown=graceful` per service;
  read/write `DataSource` split with the read-your-writes rule (§2.3); Hibernate
  prepared-statement setting for PgBouncer transaction mode (§2.2); **explicit
  `spring.datasource.hikari.*` pool block per service (~10) replacing the inherited default
  (§2.2, CHAT-110 flag 3)**; `messages` **daily** RANGE-partition Flyway migration + pg_partman
  (§2, do early); **CHAT-114 (Kafka topology fix): add `partitionKey()` to `DomainEvent`
  (→ `roomId`); collapse the current per-room-per-event-type topics into a bounded,
  per-event-type topic set; give each ws-gateway pod a fan-out-safe consumer identity (unique
  group id per pod, or manual partition assignment) instead of the shared `ws-gateway` group
  id** (§3); ws-gateway active-connection-count and per-pod consume-rate metrics for HPA;
  **bounded per-socket outbound queue with drop-and-mark-stale backpressure (§3 mega-room risk,
  CHAT-110 flag 2) — follow-up ticket.**
- **performance-engineer:** the concurrency/throughput/latency/availability targets that turn
  every "tunable" above into a number; load-test the per-pod socket ceiling that gates §1, and
  validate the CHAT-114 topic/consumer-group fix in §3 once built.
- **Reconcile:** ~~when performance-engineer's numbers land, revisit HPA thresholds, replica
  counts, partition interval, and the §3 Kafka trigger.~~ **Done 2026-07-27** against CHAT-110 —
  see the "Reconciled against performance-engineer's CHAT-110 targets" callout at the top of this
  section. One decision changed (partition interval monthly→daily); all other numbers filled as
  tunables; two risks recorded (unbounded room membership §3, default Hikari pool §2.2). Next
  reconciliation trigger: load-test results confirming/adjusting the ~20k socket and ~10k
  event/sec/pod ceilings.
