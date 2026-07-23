# How Security Works — Chattera Authentication & Authorization

This document explains, end to end, how identity and access work in Chattera:
who owns what, how a login actually happens, how each backend service
validates a request, and where each piece lives in the codebase. It expands
on the "Authentication & Identity (Keycloak)" section of
[`docs/solution-architecture.md`](solution-architecture.md) with the concrete
implementation delivered for CHAT-103.

## 1. The core idea

Chattera does not implement authentication. **Keycloak** — a separate,
pre-built identity server — owns registration, login, logout, password
storage, and token issuance. Every Chattera-built service (`gateway`,
`ws-gateway`, `profile-service`, `chat-service`, `file-service`) is a **pure
OAuth2 resource server**: it only ever *validates* tokens that Keycloak
issued. No Chattera service stores a password, mints a token, or calls
Keycloak on the hot path of a normal API request.

```
                 ┌────────────┐
  1. login  ───▶ │  Keycloak  │  (owns credentials, sessions, token issuance)
  ◀── tokens ────┤  (realm:   │
                 │  chattera) │
                 └────────────┘
                       │
        2. Bearer <access token> on every request
                       ▼
   ┌───────────────────────────────────────────┐
   │ Chattera services (resource servers only)  │
   │ gateway · ws-gateway · profile · chat · file│
   │ — validate the token locally, never call    │
   │   Keycloak per-request                      │
   └───────────────────────────────────────────┘
```

## 2. Where Keycloak runs and what it's configured with

Keycloak is one container in the local dev stack (`docker-compose.yml`,
owned by devops-engineer), running `quay.io/keycloak/keycloak:26.0` in
`start-dev --import-realm` mode, with its own isolated Postgres database and
role (`keycloak` db — no shared schema with the `chattera` application
database). It auto-imports the realm definition on startup from
[`infra/keycloak/realm-export/chattera-realm.json`](../infra/keycloak/realm-export/chattera-realm.json):

- Realm: `chattera`. Access tokens are short-lived (`accessTokenLifespan:
  300` — 5 minutes).
- Two OIDC clients, both **public** (no client secret) because neither can
  safely hold one:
  - `chattera-web` — the browser SPA. `redirectUris: ["http://localhost:3000/*"]`.
  - `chattera-mobile` — the native app. `redirectUris: ["chattera://callback"]`.
- Both clients: `standardFlowEnabled: true` (Authorization Code flow),
  `implicitFlowEnabled: false`, `directAccessGrantsEnabled: false`
  (password-grant disabled — login only happens through Keycloak's hosted
  page), `pkce.code.challenge.method: "S256"` (PKCE required, see §4).
- `registrationAllowed: true` — registration is Keycloak's own hosted
  registration page, not a Chattera-built form.

Locally it's published on `http://localhost:8080`. Full run instructions:
[`infra/README.md`](../infra/README.md). OIDC discovery document:
`http://localhost:8080/realms/chattera/.well-known/openid-configuration`.

## 3. Login flow (Authorization Code + PKCE)

1. The client (web SPA or mobile app) redirects the user's browser to
   Keycloak's authorization endpoint with `response_type=code`, its
   `redirect_uri`, and a PKCE `code_challenge` (see §4).
2. The user authenticates on **Keycloak's own hosted login page** — Chattera
   never sees the password. Registration works the same way, via Keycloak's
   hosted registration page.
3. Keycloak redirects back to the client's registered `redirect_uri` with a
   one-time authorization `code`.
4. The client exchanges that `code` (plus its PKCE `code_verifier`) at
   Keycloak's token endpoint and receives:
   - an **access token** (a signed JWT — this is what gets sent to Chattera),
   - a **refresh token** (used only against Keycloak, never sent to Chattera),
   - an **ID token** (OIDC identity assertion, for the client's own use).
5. From then on, the client calls Chattera's REST API and WebSocket
   endpoints with `Authorization: Bearer <access token>`. On WebSocket, the
   token is presented on the STOMP CONNECT frame / handshake.

Refresh: the client talks to Keycloak's token endpoint directly with the
refresh token — Chattera is not involved and holds no refresh-token store.

Logout: OIDC RP-initiated logout — the client redirects to Keycloak's
`end_session_endpoint`, which ends the Keycloak SSO session and invalidates
the refresh token. Chattera has no server-side token to revoke; a token that
can no longer be refreshed simply expires within its short access-token TTL.
(Optional future hardening, not built: Keycloak Back-Channel Logout so
`ws-gateway` can proactively drop live sockets on logout.)

## 4. What PKCE is, and why it's required here

PKCE ("Proof Key for Code Exchange", RFC 7636) is an extension to the
Authorization Code flow that protects **public clients** — apps that can't
safely hold a secret, like a browser SPA (JavaScript is inspectable) or a
mobile app (binaries can be decompiled). Without it, if the one-time
authorization `code` from step 3 above were intercepted (e.g. a malicious
app registering the same custom URL scheme on the device), the interceptor
could exchange it for tokens and impersonate the user, since nothing else
proves who's redeeming the code.

PKCE fixes this by having the client prove it's the same party that started
the flow:

1. Before redirecting to Keycloak, the client generates a random secret,
   the `code_verifier`, and keeps it locally (never sent in a URL).
2. It computes `code_challenge = SHA256(code_verifier)` and sends only the
   `code_challenge` in the initial authorization request (step 1 in §3).
3. Keycloak associates that `code_challenge` with the `code` it issues.
4. When the client exchanges the `code` for tokens (step 4 in §3), it must
   also submit the original `code_verifier`.
5. Keycloak re-hashes the submitted `code_verifier` and checks it matches
   the `code_challenge` from step 2. Only the client that generated the
   original verifier can pass this check — an interceptor holding just the
   leaked `code` cannot.

Chattera's realm config sets `pkce.code.challenge.method: "S256"` (the
SHA-256 method — the weaker `plain` method, which sends the verifier itself
as the challenge, is not used) on both clients, and both are `publicClient:
true` with no client secret specifically **because** PKCE makes that safe.
PKCE is entirely a client-↔-Keycloak concern: by the time a request reaches
a Chattera service, PKCE has already done its job, and all that's left to
check is whether the access token is validly signed (§5).

## 5. Anatomy of an access token

A JWT is three base64url-encoded segments joined by dots:
`header.payload.signature`. Keycloak signs Chattera's access tokens with
`RS256`. Decoding a real token issued during CHAT-103 testing looks like
this:

**Header** — which key/algorithm signed it:
```json
{ "alg": "RS256", "typ": "JWT", "kid": "abc123..." }
```
`kid` tells a validator which of Keycloak's public keys (from its JWKS
endpoint) to check the signature against, since keys can rotate.

**Payload (claims)** — the actual data:
```json
{
  "iss": "http://localhost:8080/realms/chattera",
  "sub": "3a0205b3-67f5-45ed-aee7-b8a1b5101a68",
  "aud": "account",
  "exp": 1753289000,
  "iat": 1753288700,
  "azp": "chattera-test-client",
  "preferred_username": "testuser",
  "realm_access": { "roles": ["default-roles-chattera", "offline_access"] },
  "scope": "openid"
}
```

Two groups of claims:
- **Standard OIDC claims**: `iss` (issuer — checked against `issuer-uri`),
  `sub` (the durable user id — see §8), `aud` (intended audience — **not
  currently validated**, see the callout in §6), `exp`/`iat` (expiry,
  ~5 minutes per §2), `azp` (which client requested the token).
- **Keycloak-specific claims**: `preferred_username`/`name` (used as the
  JIT-provisioning displayName fallback, §8), `realm_access.roles` (mapped
  to Spring authorities, §7).

**Signature** — Keycloak signs `base64url(header) + "." + base64url(payload)`
with the realm's RSA private key. A service verifies it by fetching the
matching public key from Keycloak's JWKS endpoint
(`/realms/chattera/protocol/openid-connect/certs`, auto-discovered from
`issuer-uri`) and checking the signature — this is the "local, no
per-request call to Keycloak" check described in §6.

## 6. How a Chattera service validates a token (resource server side)

Every service that needs authentication declares
`spring-boot-starter-oauth2-resource-server` and one property:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/chattera
```

(`profile-service`'s actual config, in
[`services/profile-service/src/main/resources/application.yml`](../services/profile-service/src/main/resources/application.yml),
makes this externally overridable via the `KEYCLOAK_ISSUER_URI` env var,
since devops-engineer's Keycloak host isn't the same across environments.)

Given just `issuer-uri`, Spring Security automatically:

1. Fetches `/.well-known/openid-configuration` from Keycloak once, to learn
   the JWKS URI, token endpoint, etc.
2. Fetches and caches Keycloak's public signing keys (the JWKS).
3. On every incoming request, validates the `Authorization: Bearer` JWT's:
   - **signature**, against the cached public keys,
   - **issuer** (`iss` claim matches `issuer-uri`),
   - **expiry** (`exp` claim, ~5 minutes per §2).

This is a **local check** — Keycloak is not called per request. That's what
lets every service stay stateless and horizontally scalable behind a load
balancer (per the baseline in `docs/solution-architecture.md`). The gateway
may additionally pre-validate at the edge, but each service still validates
independently (defense in depth — a service must not implicitly trust
"reachable only via the gateway").

Each service still owns its own authorization rules (which paths require
auth). `profile-service`'s filter chain
([`services/profile-service/src/main/java/com/chattera/profile/config/SecurityConfig.java`](../services/profile-service/src/main/java/com/chattera/profile/config/SecurityConfig.java)):

```java
http
    .csrf(csrf -> csrf.disable())                    // stateless JWT, no cookies/session -> no CSRF risk
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .anyRequest().authenticated())
    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
```

### 6.1 The request pipeline, concretely — where does "the interceptor" live?

None of this validation logic is code written in this repo — it's Spring
Security framework machinery, *wired in* by the one-line
`.oauth2ResourceServer(...)` call above. `SecurityFilterChain` itself isn't
a class to extend; it's an **ordered list of `Filter` objects** that
`http.build()` assembles. `.oauth2ResourceServer(...)` inserts one specific
filter into that list — it doesn't replace or subclass the chain.

```
HTTP request: GET /me, Authorization: Bearer <token>
        │
        ▼
BearerTokenAuthenticationFilter   (extends OncePerRequestFilter — one Filter
        │                          among several in the SecurityFilterChain
        │                          profile-service's SecurityConfig builds)
        │  extracts the raw token string from the Authorization header
        ▼
JwtAuthenticationProvider.authenticate(...)
        │  1. JwtDecoder.decode(token)                — a NimbusJwtDecoder,
        │     auto-configured from issuer-uri alone. Validates signature
        │     (against Keycloak's cached JWKS), iss, exp. Produces a Jwt
        │     object (see §5's claim structure).
        │  2. jwtAuthenticationConverter.convert(jwt)  — the common-security
        │     bean (§7): reads realm_access.roles, produces GrantedAuthority
        │     objects.
        ▼
Authentication object → stored in SecurityContext for this request
        ▼
AuthorizationFilter   (the next link in the chain — enforces
        │              .anyRequest().authenticated() from SecurityConfig)
        ▼
DispatcherServlet routes GET /me → ProfileController.getMe(...)
        │  @GetMapping matches the HTTP method + path to this method.
        │  @AuthenticationPrincipal Jwt jwt is resolved by pulling the Jwt
        │  back out of the SecurityContext the filter chain already
        │  populated — the controller never touches the raw header itself.
        ▼
getOrProvision(jwt) runs, now holding a cryptographically-verified Jwt
```

If the token is missing, malformed, or fails any of those checks,
`BearerTokenAuthenticationFilter`/`AuthorizationFilter` short-circuit the
chain with `401` before `DispatcherServlet` ever routes to
`ProfileController` — the controller method body never runs at all for an
invalid request. `BearerTokenAuthenticationFilter`, `JwtDecoder`, and
`JwtAuthenticationProvider` all live inside Spring Security's own jars
(`spring-security-oauth2-resource-server`, `spring-security-oauth2-jose` —
in your local `~/.m2` cache, not `services/` or `platform/`); the only
application code in this whole pipeline is `SecurityConfig`'s 15 lines and
the `common-security` converter in §7.

**Known gap** (code-reviewer finding, not yet fixed): step 1 above validates
`iss`/`exp`/signature but not `aud` (audience). Any token issued to *any*
client in the `chattera` realm is currently accepted by every resource
server, not just the client it was meant for. Low blast-radius for
`profile-service` alone (every endpoint only acts on the caller's own
`sub`), but flagged as a shared-pattern decision for solution-architect
before other services copy this same `SecurityConfig`.

## 7. Mapping token claims to Spring authorities (shared code)

Keycloak embeds the user's realm roles in the access token as
`realm_access.roles`. `platform/common-security` — a shared library every
service can depend on — turns that into Spring `GrantedAuthority` objects,
so role-based checks (`@PreAuthorize`, etc.) work the same way in every
service without each one reimplementing it:

[`KeycloakRealmRoleConverter`](../platform/common-security/src/main/java/com/chattera/security/KeycloakRealmRoleConverter.java) —
reads `realm_access.roles` and prefixes each with `ROLE_` (Spring
convention), e.g. Keycloak role `admin` → Spring authority `ROLE_ADMIN`.
(Client/resource-level roles, `resource_access`, are not mapped — no Sprint
1 requirement needs them yet.)

[`JwtAuthenticationConverterAutoConfiguration`](../platform/common-security/src/main/java/com/chattera/security/JwtAuthenticationConverterAutoConfiguration.java) —
a Spring Boot `@AutoConfiguration` that registers a
`Converter<Jwt, AbstractAuthenticationToken>` bean wired with the role
converter above. Because it's registered via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
any service just needs the `common-security` + resource-server
dependencies — the converter shows up automatically, no manual `@Bean`
needed (a service can still override it by declaring its own bean of that
type).

## 8. Who a user "is" to Chattera: the `sub` claim and JIT provisioning

Keycloak's `sub` claim (a stable UUID) is the only user identifier Chattera
relies on. Chattera does **not** ask Keycloak's Admin API whether a user
exists — instead, `profile-service` provisions a profile row itself,
just-in-time, the first time it sees a validly-signed token for a `sub` it
doesn't have a row for:

[`ProfileService.getOrProvision`](../services/profile-service/src/main/java/com/chattera/profile/service/ProfileService.java):

```java
public Profile getOrProvision(Jwt jwt) {
    String userId = jwt.getSubject();                 // the Keycloak sub
    return profileRepository.findById(userId)
            .orElseGet(() -> provision(userId, jwt));
}

private Profile provision(String userId, Jwt jwt) {
    String defaultDisplayName = jwt.getClaimAsString("preferred_username");
    if (defaultDisplayName == null) {
        defaultDisplayName = jwt.getClaimAsString("name");
    }
    try {
        return self.insertNewProfile(userId, defaultDisplayName);
    } catch (DataIntegrityViolationException duplicateUserId) {
        // lost the provisioning race - see "Fixed bug" below
        return profileRepository.findById(userId).orElseThrow(() -> duplicateUserId);
    }
}
```

(`self.insertNewProfile(...)`, not shown in full here, is the actual
`profileRepository.save(new Profile(...))` call - see "Fixed bug" below for
why it's split out into its own method and transaction.)

This means the *first* authenticated `GET /me` or `PUT /me` a new user makes
silently creates their profile row — there is no separate "create account in
Chattera" step. This avoids needing a confidential Keycloak client or any
Admin API service-account credential in Sprint 1. Both `ProfileController`
endpoints go through this same method — `getMe` calls it directly, and
`updateProfile` calls it first to fetch-or-create the row before applying
`PUT` changes — so neither endpoint is "the provisioning one," both are.

**Fixed bug** (code-reviewer finding, CONFIRMED, now fixed): the `findById`
check and the insert inside `provision()` are two separate statements, not
one atomic operation — `@Transactional` only makes a single call to
`getOrProvision` atomic, it does not serialize two *concurrent* calls
against each other. If two requests for the same brand-new `userId` land
close together (realistic on SPA startup — e.g. two components each
independently requesting profile data):

```
Time  Request A                          Request B
----  ---------------------------        ---------------------------
t0    findById(x) → empty
t1                                       findById(x) → empty   (A hasn't
                                                                 committed
                                                                 yet, so B
                                                                 also sees
                                                                 no row)
t2    INSERT profiles(user_id=x)
t3    COMMIT ✅
t4                                       INSERT profiles(user_id=x)
t5                                       ❌ duplicate key on the PK
```

The database's primary-key constraint is what actually catches this, not
application logic. Unhandled, the resulting `DataIntegrityViolationException`
would surface as a bare `500` on what is, from the user's perspective, their
very first login (`GlobalExceptionHandler` only maps
`MethodArgumentNotValidException`, so it wouldn't have caught this either).

Fix applied: catch-and-retry, rather than `INSERT ... ON CONFLICT (user_id)
DO NOTHING`. `ON CONFLICT` was tried first but rejected - it's Postgres-only
syntax that the embedded H2 database `profile-service`'s `@DataJpaTest` suite
runs against (no Docker/Postgres required for `mvn test`, see the repo's
`CLAUDE.md`) doesn't understand, so it broke the test suite. The catch-and-
retry version has its own trap worth calling out: on PostgreSQL, a failed
`INSERT` aborts the *whole* enclosing transaction, not just that statement,
so naively catching the exception and re-reading inside the same
`@Transactional` method would still blow up against real Postgres (`current
transaction is aborted...`). `ProfileService.provision()` avoids that by
running the insert attempt (`insertNewProfile`) in its own
`@Transactional(propagation = REQUIRES_NEW)` transaction via a `@Lazy`
self-injected proxy reference (a plain `this.insertNewProfile(...)` call
would bypass the Spring AOP proxy and silently ignore that annotation) - so
a duplicate-key failure there only rolls back that nested transaction, and
the follow-up `findById` re-read (returning the winner's already-committed
row) runs cleanly. Covered by
[`ProfileServiceConcurrentProvisioningTest`](../../services/profile-service/src/test/java/com/chattera/profile/service/ProfileServiceConcurrentProvisioningTest.java),
which drives two real concurrent transactions against the embedded H2
database rather than mocking the repository.

## 9. What Chattera explicitly does NOT build

Per the design in `docs/solution-architecture.md`, the following are
Keycloak's responsibility and are intentionally absent from every Chattera
service:

- Registration, login, or logout endpoints
- Password hashing or storage
- Token issuance (access, refresh, or ID tokens)
- Refresh-token rotation/revocation or SSO session storage

If a future ticket appears to require any of the above inside a Chattera
service, that's a signal the design has drifted from this doc — flag it to
solution-architect rather than implementing it.

## 10. Local dev / running it end to end

```
cp .env.example .env      # first time only
docker compose up -d      # brings up Postgres, Redis, MinIO, and Keycloak (realm chattera)
```

Then a service can point at Keycloak with no extra config — defaults
already match:

```
mvn install -DskipTests               # once, from repo root
cd services/profile-service && mvn spring-boot:run
```

Verify Keycloak imported correctly: `curl
http://localhost:8080/realms/chattera/.well-known/openid-configuration`
should return a JSON document with `"issuer":
"http://localhost:8080/realms/chattera"`. Full details:
[`infra/README.md`](../infra/README.md).

To actually obtain a Bearer token by hand for testing (no frontend yet),
drive the Authorization Code + PKCE flow against
`http://localhost:8080/realms/chattera/protocol/openid-connect/auth` using
the `chattera-web` client id and a local PKCE verifier/challenge pair, or
use a tool that automates it (e.g. Postman's OAuth2 helper, or `oidc-client`
scripts) — there is no password-grant fallback available (it's disabled on
both clients by design, matching production behavior).

## 11. Code map

| Concern | Location |
|---|---|
| Full design writeup | [`docs/solution-architecture.md`](solution-architecture.md) §"Authentication & Identity (Keycloak)" |
| Realm/client definition | [`infra/keycloak/realm-export/chattera-realm.json`](../infra/keycloak/realm-export/chattera-realm.json) |
| Local infra (Keycloak container, Postgres, Redis) | [`docker-compose.yml`](../docker-compose.yml), [`infra/README.md`](../infra/README.md) |
| Role → authority mapping (shared) | [`platform/common-security/src/main/java/com/chattera/security/`](../platform/common-security/src/main/java/com/chattera/security/) |
| Resource-server wiring (profile-service) | [`services/profile-service/src/main/java/com/chattera/profile/config/SecurityConfig.java`](../services/profile-service/src/main/java/com/chattera/profile/config/SecurityConfig.java) |
| JIT profile provisioning | [`services/profile-service/src/main/java/com/chattera/profile/service/ProfileService.java`](../services/profile-service/src/main/java/com/chattera/profile/service/ProfileService.java) |
| `GET`/`PUT /me` routing (`@GetMapping`/`@PutMapping`), `@AuthenticationPrincipal` | [`services/profile-service/src/main/java/com/chattera/profile/web/ProfileController.java`](../services/profile-service/src/main/java/com/chattera/profile/web/ProfileController.java) |
| `issuer-uri` / datasource / Redis config | [`services/profile-service/src/main/resources/application.yml`](../services/profile-service/src/main/resources/application.yml) |
| Security-focused tests | [`services/profile-service/src/test/java/com/chattera/profile/web/ProfileControllerTest.java`](../services/profile-service/src/test/java/com/chattera/profile/web/ProfileControllerTest.java) |

## 12. Open items / deferred

**Pending fixes from code review (CHAT-103), not yet applied:**

- **JIT-provisioning race condition** (§8) — concurrent first-requests for
  the same new user can 500 instead of gracefully resolving. Owner:
  developer.
- **No `aud` (audience) claim validation** (§6.1) — every resource server
  currently accepts any token from the `chattera` realm regardless of which
  client it was issued to. Owner: solution-architect (shared-pattern
  decision for `common-security`, since other services will copy this
  config).
- **`Locale`-dependent role mapping** in `KeycloakRealmRoleConverter` —
  `role.toUpperCase()` with no `Locale.ROOT` can mis-map roles under
  non-English JVM locales. Owner: developer.
- **Presence-read failure isn't isolated** — a Redis outage currently fails
  the entire `/me` read/update instead of degrading presence to
  unknown/offline. Owner: developer.

Noted in `docs/solution-architecture.md` and not built yet:

- **Web token storage hardening**: today (`Option A`) the SPA holds tokens
  in browser memory with silent refresh. A Backend-for-Frontend (`Option
  B`) — a confidential client + httpOnly session cookie, tokens never
  reaching JS — is a stronger but stateful alternative, deferred past
  Sprint 1.
- **Keycloak Back-Channel Logout**, so `ws-gateway` proactively closes live
  sockets on logout instead of waiting for the access token to expire.
- **Resource/client-level role mapping** (`resource_access` claim) — only
  realm roles are mapped today; add this only if a concrete authorization
  need for it appears.
- Gateway/ws-gateway/chat-service/file-service are currently scaffolds only
  (they don't yet declare `spring-boot-starter-oauth2-resource-server` or
  wire `common-security`'s converter) — that wiring lands with their own
  implementation tickets, following the same pattern as `profile-service`.
