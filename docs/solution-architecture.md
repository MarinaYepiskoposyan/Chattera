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
- Event bus: **RabbitMQ via Spring AMQP** (decided CHAT-104 — see "Chat Rooms & Messaging"
  below). chat-service is the first producer onto the backbone; the `EventPublisher`
  abstraction in `platform/common-messaging` gets its first concrete transport here.
  Rationale and the options weighed are documented in the CHAT-104 section. WebSocket
  delivery (ws-gateway) uses Spring WebSocket + STOMP with RabbitMQ's STOMP broker relay so
  cross-instance fanout is handled by the broker rather than in app code (wired in CHAT-107).

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

### Event bus decision — RabbitMQ (Spring AMQP)
Decided here because CHAT-104 is the first producer and the choice blocks implementation.
Options weighed:
- **RabbitMQ (chosen).** Chat delivery is a routing/fanout problem — one room message fans
  out to N member sockets, one DM routes to a single recipient's sockets — which is exactly
  the topic-exchange model. Spring AMQP is first-class and matches the low-ceremony DX of
  the existing services. Its STOMP broker relay lets multiple ws-gateway instances all
  receive a published message with no app-level coordination, which is what keeps ws-gateway
  stateless behind the load balancer (CHAT-107). Modest operational footprint (one broker
  container added to `docker-compose.yml`, owned with devops-engineer).
- **Kafka (rejected for Sprint 1).** Its strengths — partitioned durable log, replay,
  high-throughput streaming — are the 1M-scale concerns that are explicitly deferred.
  Adopting it now is premature commitment and a heavier ops burden for MVP.
- **Redis Streams/Pub-Sub (rejected).** Pub/Sub is fire-and-forget (a message published
  while ws-gateway is momentarily down is lost); Streams would work but is lower-level with
  weaker Spring ergonomics, and it concentrates cache + presence + durable bus onto one
  Redis instance. Not worth it when RabbitMQ fits the fanout model directly.

**The bus is transient delivery, not the message store.** PostgreSQL remains the single
source of truth for message history. A message is persisted (committed) *before* it is
published; a dropped/undelivered event never loses data because clients re-fetch recent
history on (re)connect. This keeps RabbitMQ durability requirements modest for Sprint 1.
Publish is best-effort: a publish failure is logged and must **not** fail the REST write.
A transactional outbox (publish exactly-once, tied to the DB commit) is noted as later
hardening — deferred, not Sprint 1.

### Scope split: CHAT-104 vs CHAT-107 (confirmed)
- **CHAT-104 owns** the REST + persistence + producer side, and is independently
  buildable/testable without CHAT-107:
  - REST: create room, join room, leave room, list a room's members, post a message,
    fetch message history.
  - Persistence: rooms, room membership, messages (schema below).
  - Producing: after the message row commits, publish a `RoomMessageCreated` event to the
    RabbitMQ topic exchange (routing key by room). If no consumer/queue is bound yet
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
receipts in CHAT-104.

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
link is RabbitMQ: chat-service is the **producer**, ws-gateway is the **consumer**. This
decoupling is deliberate and is what lets both tiers be stateless and scale independently.
- **chat-service** = writer / system of record: REST writes, authz, business rules,
  Postgres persistence, and publishing events. **ws-gateway** = delivery edge: holds live
  sockets, tracks presence, pushes events to clients. No business logic, no DB.
- **The contract between them is the event, not an API** — its fields + routing key, defined
  via `common-messaging`/`common-domain` (`DomainEvent`, e.g. `RoomMessageCreated`). The
  event is **self-contained** (id, sender, content, timestamp, status), so ws-gateway
  forwards it without querying Postgres or calling chat-service.
- **End-to-end loop** (through both, but they never touch directly):
  `Client --REST POST--> chat-service --publish--> RabbitMQ --route--> ws-gateway --WS push--> Client`.
  Any chat-service instance can publish; any ws-gateway instance can deliver; the broker
  bridges any-to-any.
- **ws-gateway statelessness** — a live socket is unavoidably transient state on one
  instance, but no *authoritative* state lives there (history→Postgres, presence→Redis,
  routing→RabbitMQ, identity→the JWT). If an instance dies, the client reconnects to **any**
  other instance (no sticky sessions), re-CONNECTs, re-SUBSCRIBEs, and re-fetches recent
  history — nothing is lost. A message sent during the reconnect gap is missed on the socket
  but recovered from history, which is again why persist-first + Postgres-as-source-of-truth
  matters. (Consumer queue topology to avoid the single-shared-queue "competing consumers"
  trap is a CHAT-107 concern — each ws-gateway instance needs its own queue bound to the
  exchange, handled by the STOMP broker relay, not one shared queue.)

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

## Sprint 1 Architecture Focus
- define API contracts
- define authentication flow
- create baseline deployment and observability setup
- establish integration points between chat and file services

## Scalability Note
The detailed scalability plan for 1,000,000 users will be discussed separately and will cover sharding, load balancing, data partitioning, and capacity planning.

### Known limits / scaling considerations (recorded, not yet designed)
These are constraints identified while making the Sprint 1 baseline decisions. They are
**deliberately not solved here** — they belong to the deferred scalability track and should
be picked up with performance-engineer (throughput/concurrency targets) before any design.
Recorded so the baseline's boundaries are on the record.

- **Room count vs. active-room fanout are different problems.** Millions of rooms *in the
  database* is a normal relational-scale concern (rows in `rooms`/`room_members`/`messages`
  plus the keyset index); the eventual pressure there is total *message* volume →
  `messages` table partitioning, not room count. What stresses the **messaging backbone** is
  the much smaller number of rooms with *live WebSocket subscribers at the same time*.
- **RabbitMQ queue/binding count is the backbone's soft ceiling.** The CHAT-107 fanout
  topology (topic exchange + a per-instance/per-subscription queue, via the STOMP broker
  relay) is comfortable at thousands–tens-of-thousands of concurrently active destinations,
  but *millions of simultaneously bound destinations* is a known RabbitMQ pain point.
  Directions to evaluate when this becomes real (sketch only, no decision):
  consistent-hash exchange / broker sharding; a fixed per-instance queue with room→routing-
  key hashing and app-side filtering (caps queue/binding count); or revisiting Kafka with a
  fixed partition count + room-hash partitioning. Choosing among these is deferred work.
- **Why the baseline doesn't box us in.** Producers publish through the `EventPublisher`
  abstraction (`platform/common-messaging`), not broker APIs directly; PostgreSQL is the
  source of truth while the bus is transient delivery; services are stateless; messages
  already carry a room-scoped routing key. So a future transport/topology change is
  contained rather than a rewrite — which is the reason the right-sized RabbitMQ was
  acceptable for the MVP.
