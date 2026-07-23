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

Proposed, pending Sprint 1 refinement with developer/qa-engineer:
- Build tooling: Gradle (Kotlin DSL) multi-module monorepo — lean, pending team
  familiarity confirmation (Maven multi-module is an acceptable fallback).
- Data access: Spring Data JPA/Hibernate for CRUD velocity in Sprint 1; the message
  read/history hot path may later move to jOOQ or JdbcClient (revisit with
  performance-engineer). Final call left to developer.
- Event bus: RabbitMQ (lean) for delivery fanout/routing via Spring AMQP; Kafka is the
  alternative if the team wants to commit to the streaming backbone now (that leans into
  the deferred 1M-scale work). Redis Streams/Pub-Sub is the minimal-footprint fallback.
- WebSocket protocol: Spring WebSocket + STOMP in ws-gateway; if RabbitMQ is chosen, use
  its STOMP broker relay so cross-instance fanout is handled by the broker.

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
validate signature (via the realm JWKS), issuer, expiry, and audience locally — no call
to Keycloak per request, no shared session store. Config shape (per service):

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

## Sprint 1 Architecture Focus
- define API contracts
- define authentication flow
- create baseline deployment and observability setup
- establish integration points between chat and file services

## Scalability Note
The detailed scalability plan for 1,000,000 users will be discussed separately and will cover sharding, load balancing, data partitioning, and capacity planning.
