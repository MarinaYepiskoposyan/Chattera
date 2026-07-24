# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

Chattera has moved past pre-implementation: a Java 21 / Spring Boot 3.5.x Maven multi-module monorepo exists under `platform/` (shared libraries) and `services/` (deployable services), alongside the local infra stack under `infra/` and `docker-compose.yml`. `profile-service` (CHAT-103) and `chat-service` (CHAT-104: room create/join/leave, message post + keyset-paginated history, best-effort publish to RabbitMQ after commit — no WebSocket/consumer side yet, that's CHAT-107) are fully implemented; `gateway`, `ws-gateway`, and `file-service` are scaffolds only (an empty Spring Boot app exposing `/actuator/health`, no business logic yet — implemented in later tickets). Check the current state of a given module before assuming behavior beyond what's described below; `docs/` remains the source of truth for scope not yet built.

## What Chattera Is

A scalable chat application (target: up to 1,000,000 users) supporting:
- Public/private chat rooms with multiple participants and message history
- One-to-one direct messaging with real-time delivery
- File sharing (upload, storage, metadata, download) accessible through chat history
- Message status (sent/delivered/read) and in-app notifications

Full requirements are in [docs/functional-requirements.md](docs/functional-requirements.md).

## Intended Architecture

Per [docs/solution-architecture.md](docs/solution-architecture.md), Chattera is planned as a modular, service-oriented real-time messaging platform:

Five stateless, deployable services behind a load balancer (presence is not a
standalone service — it is carried by the ws-gateway via a Redis presence key):

- **Identity provider (Keycloak)** — external OIDC provider owning registration,
  credentials, login/logout, token issuance, refresh, and SSO sessions. Chattera builds no
  auth/token logic. Keycloak deployment is owned by devops-engineer.
- **Gateway (REST)** — REST edge: request routing, rate limiting, OAuth2 token validation
- **WS-gateway (real-time)** — WebSocket edge for message delivery, typing, and presence
- **Profile service** (formerly "auth service") — Chattera-owned profile data
  (displayName, avatarUrl, timezone, bio) keyed to the Keycloak `sub`, plus online/offline
  reporting from the Redis presence key. No passwords, no token issuance.
- **Chat service** — room and private messaging workflows, persistence and history
- **File service** — upload, storage, metadata, download
- **Messaging backbone** — event bus for async, event-driven real-time delivery
- **Data layer** — PostgreSQL for profiles/metadata, Redis for cache/presence (no refresh
  tokens — Keycloak holds those)
- **Object storage** — MinIO (S3-compatible) in dev; DB holds references only
- **Observability** — logging, tracing, metrics, alerting

Technology baseline (decided): Java 21 (LTS) + Spring Boot 3.5.x. Identity/auth is
delegated to Keycloak (external OIDC provider); every Chattera service is a pure OAuth2
resource server (spring-boot-starter-oauth2-resource-server, `issuer-uri` -> Keycloak
realm). Clients log in via Authorization Code + PKCE. There is no Chattera-issued JWT and
no Redis-backed refresh token — those are superseded by Keycloak. PostgreSQL + Flyway,
Redis (cache/presence), MinIO with presigned URLs. Build tool (Maven multi-module) and
event bus (**RabbitMQ via Spring AMQP**, decided CHAT-104 — chat-service is the first
producer) are settled; the data-access library (JPA baseline, hot-path revisit) is still
open pending Sprint 1 refinement — see
[docs/solution-architecture.md](docs/solution-architecture.md).

Design principles carried forward from the architecture doc:
- Stateless application services behind a load balancer
- Real-time WebSocket traffic kept separate from REST API traffic
- Async/event-driven processing for delivery and notifications
- Durable store for message metadata, with caching for hot conversations
- File objects live in object storage; only references are persisted in the database

Core flows:
- Room message: client → API → chat service → event bus → subscribers
- Private message: client → API → chat service → event bus → recipient session
- File upload: client → API → file service → object storage → metadata persistence
- Login: client → Keycloak (Authorization Code + PKCE) → tokens → Chattera services called
  with `Authorization: Bearer <access token>`; profile row is created just-in-time on first
  authenticated request, keyed by the token `sub`.

The detailed scalability plan (sharding, partitioning, capacity planning for 1M users) is explicitly deferred and not yet designed — don't assume a specific scaling approach beyond the baseline above.

## Sprint 1 Scope

Per [docs/sprint-1-plan.md](docs/sprint-1-plan.md) and [docs/jira-sprint-board.md](docs/jira-sprint-board.md), Sprint 1 (2026-07-22 to 2026-08-05) targets the MVP foundation: auth/profiles, chat rooms, one-to-one messaging, basic file upload/download, and a baseline CI/CD + observability setup. Explicitly out of scope for Sprint 1: global-scale deployment design, advanced moderation/admin tooling, enterprise compliance workflows, and the detailed scalability architecture.

Backlog items are tracked in [docs/jira-backlog.csv](docs/jira-backlog.csv) / [docs/jira-import.csv](docs/jira-import.csv) and mirrored in [docs/jira-sprint-board.md](docs/jira-sprint-board.md) (ticket prefix `CHAT-`).

## Specialized Subagents

`.claude/agents/` defines a team of Claude Code subagents mirroring the roles in [docs/agent-roles.md](docs/agent-roles.md). Each is scoped to one responsibility and its file documents a "Coordinates with" section describing handoffs to the others:

- `orchestrator` (opus) — routing planner for requests spanning multiple roles or not clearly owned by one agent. Subagents have no delegation tool of their own, so it returns a routing plan (which agents, in what order, why) rather than invoking them; the calling session executes that plan by invoking each agent below in turn.
- `solution-architect` (opus) — service boundaries, API contracts, architecture tradeoffs.
- `business-analyst` (sonnet) — requirements, user stories, acceptance criteria.
- `developer` (sonnet) — implementation across auth/chat/presence/file services.
- `code-reviewer` (opus) — reviews diffs for correctness, security, and architecture-fit; reports via the `ReportFindings` tool rather than editing code.
- `qa-engineer` (sonnet) — test strategy, acceptance validation, defect reports.
- `devops-engineer` (sonnet) — CI/CD, environments, observability.
- `performance-engineer` (sonnet) — load testing strategy, capacity/latency targets.
- `scrum-master` (haiku) — sprint board (`docs/jira-sprint-board.md`) and backlog upkeep.
- `project-manager` (sonnet) — milestone tracking and stakeholder status reporting.

The standard flow for a feature is `solution-architect` → `business-analyst` → `developer` → `code-reviewer` → `qa-engineer` → `scrum-master`, skipping steps already satisfied (e.g., skip `solution-architect` if the design is already settled in `docs/solution-architecture.md`).

## Working in This Repo

- Keep new code aligned with the service boundaries in the architecture doc (auth, chat, presence, file, gateway) rather than building a monolith, unless the user directs otherwise.
- Treat `docs/` as the source of truth for scope and design intent not yet superseded by actual implementation.
- Shared code goes in `platform/` (`common-domain`, `common-security`, `common-messaging`, `common-observability`) and is consumed by `services/*` as regular Maven dependencies, not copy-pasted.
- `common-security` auto-configures JWT handling for any service that depends on it and sets `issuer-uri`: the `realm_access.roles` → authority converter **and** (CHAT-28) a `JwtDecoder` that validates the token's `azp` (authorized party) against an allowlist of sanctioned Keycloak clients (`chattera.security.jwt.accepted-client-ids`). Services inherit both automatically — do not re-add `aud`/`azp` checks per service. See [docs/guides/how-security-works.md](docs/guides/how-security-works.md) §6.2. Note: a service's own `JwtDecoder` bean overrides the shared one, and web-slice tests still supply their own decoder as they do today. chat-service (CHAT-104) reused this with zero extra config beyond its own `SecurityFilterChain` — confirms the pattern generalizes past profile-service.
- `common-messaging` (CHAT-104) auto-configures a RabbitMQ-backed `EventPublisher<DomainEvent>` for any service that has `spring-boot-starter-amqp` on the classpath (pulled in transitively by depending on `common-messaging`) and standard `spring.rabbitmq.*` connection properties set — same auto-configuration-by-dependency pattern as `common-security`. Publishing is to one shared topic exchange (`chattera.events` by default, `chattera.messaging.exchange` to override) with the caller's routing key; `EventPublisher.publish` never throws (transport failures are logged and swallowed) since the bus is transient delivery and Postgres is the source of truth — see solution-architecture.md's CHAT-104 section for the persist-then-publish rationale. Shared domain event payloads (e.g. `RoomMessageCreatedEvent`) live in `common-domain`'s `event` package so future consumers (ws-gateway, CHAT-107) can depend on the same on-the-wire type without depending on the producing service.
- Services sharing the one dev Postgres database (`chattera`) each get their **own Postgres schema**, not the default `public` schema — `profile-service` implicitly uses `public` (unchanged, first mover), `chat-service` explicitly targets `chat_service` via `spring.flyway.schemas` + `spring.jpa.properties.hibernate.default_schema` in its `application.yml`. Without this, two services' Flyway histories collide on version numbers (both start at `V1__...`) in the same `flyway_schema_history` table, since Flyway's history table isn't itself namespaced per service. Any new service adding its own schema/migrations should follow chat-service's pattern (pick a distinct schema name) rather than profile-service's implicit-`public` one. `@DataJpaTest`-based tests should use `@TestPropertySource` (or a shared composed annotation — see chat-service's `ChatDataJpaTest`) to reset both properties back to unset, since the embedded H2 substitute per test doesn't need or have a `chat_service` schema.

### Build/test (Maven multi-module monorepo)

**Prerequisite — JDK 21 (LTS) is a hard requirement, not optional.** The root `pom.xml`
pins `maven.compiler.release=21`; building with an older JDK (e.g. the JDK 17 many dev
machines default to) fails outright. Check `java -version` / `JAVA_HOME` *before* running
any `mvn` command — don't assume the host's default JDK is 21. Maven itself (any recent
3.9.x) is otherwise unremarkable.

> **Windows/headless trap:** `winget install EclipseAdoptium.Temurin.21.JDK` triggers a UAC
> elevation prompt (`consent.exe`) that has nothing to approve it in a non-interactive/CI/
> scripted session — the install then hangs indefinitely rather than failing loudly (the
> underlying `msiexec` shows near-zero CPU time even after several minutes; it's genuinely
> stuck, not slow). **Recommended workaround for headless/CI/scripted setup:** download the
> portable Temurin 21 zip distribution (no admin/elevation required) and point `JAVA_HOME`
> at the extracted folder instead of using `winget`. Interactive installs where a human can
> click through the UAC prompt are unaffected. This also means any CI build image should
> **bake JDK 21 in at image-build time** (e.g. `FROM eclipse-temurin:21-jdk` or equivalent)
> rather than installing it as a pipeline step — see CHAT-108.

From the repo root:

```
mvn clean install          # build + test every module
mvn -pl services/profile-service -am test   # just profile-service (and its deps)
```

Each service module produces an executable Spring Boot jar under `services/<service>/target/`. `-pl <module> -am spring-boot:run` from the root does **not** work on this multi-module layout — it binds the goal to the aggregator POM and fails with "Unable to find a suitable main class". Run a service like this instead:

```
mvn install -DskipTests               # once, from repo root — populates platform/* into .m2
cd services/profile-service
mvn spring-boot:run
```

or `java -jar services/profile-service/target/profile-service-0.1.0-SNAPSHOT.jar` after a full build. Same pattern for chat-service (`cd services/chat-service && mvn spring-boot:run`).

profile-service also needs the local infra stack up (Postgres + Redis + Keycloak — see below) and reads connection info from environment variables with dev-friendly defaults baked into `application.yml` (`SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`, `SPRING_DATA_REDIS_HOST`/`_PORT`, `KEYCLOAK_ISSUER_URI`). chat-service needs Postgres + Keycloak (same env vars) plus RabbitMQ (`SPRING_RABBITMQ_HOST`/`_PORT`/`_USERNAME`/`_PASSWORD`) — a broker outage doesn't block REST traffic (see the `common-messaging` note above), it just means published events go nowhere. Defaults already match `docker-compose.yml`/`.env.example`, so `docker compose up -d` followed by the two commands above works with no extra env vars. Ports: gateway 8090, ws-gateway 8081, profile-service 8082, chat-service 8083, file-service 8084 (8080 is reserved for Keycloak locally; RabbitMQ is 5672 (AMQP) / 15672 (management UI), not a per-service HTTP port).

No lint tool is configured yet (flag if one is wanted — e.g. Checkstyle/Spotless — rather than assuming).

### Local infrastructure (Postgres, Redis, MinIO, RabbitMQ, Keycloak)

Needed alongside the Maven build above for anything that touches Postgres/Redis/RabbitMQ/Keycloak (e.g. running profile-service or chat-service; their `@DataJpaTest`-only test suites do *not* need this — they use an embedded H2 substitute). From the repo root:

```
cp .env.example .env   # first time only; edit values if desired
docker compose up -d
```

Brings up Postgres (app `chattera` db + a separate least-privilege `keycloak` db/role — never a shared schema; see the `common-messaging`/schema-per-service note above for how services then isolate themselves *within* that one `chattera` app database), Redis, MinIO, RabbitMQ (`rabbitmq:3.13-management-alpine`, CHAT-104's event bus — chat-service is the first producer), and a self-hosted Keycloak (`quay.io/keycloak/keycloak:26.0`, `start-dev --import-realm`) auto-loaded with the `chattera` realm from [`infra/keycloak/realm-export/chattera-realm.json`](infra/keycloak/realm-export/chattera-realm.json) (clients `chattera-web` and `chattera-mobile`, both public, Authorization Code + PKCE S256, no client secret). Full usage, ports, and verification steps: [`infra/README.md`](infra/README.md). Point a service's resource-server config at `issuer-uri: http://localhost:8080/realms/chattera` for local token validation.

For headless smoke testing against a running service (e.g. `profile-service`) without a browser-driven PKCE flow, the realm also has a **dev/test-only** confidential client, `chattera-test-client` (Resource Owner Password Credentials / `directAccessGrantsEnabled: true`), and a `testuser` account. This exists only for local automated testing — never enable `directAccessGrantsEnabled` on a client in staging/production. Credentials and the token curl command are in [`infra/README.md`](infra/README.md#dev-test-only-getting-a-token-without-a-browser) (values also live in `.env`, gitignored).