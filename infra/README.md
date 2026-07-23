# Chattera Local Infrastructure

This is the first infrastructure code in the repo: a `docker-compose.yml` at
the repo root that brings up the shared dependencies every Chattera service
needs locally — Postgres, Redis, MinIO, and a self-hosted Keycloak instance
pre-loaded with the `chattera` realm. It does **not** run any Chattera
application service (gateway/ws-gateway/profile/chat/file) — those run
separately (see the Java services under `services/` once scaffolded) and
point at this stack via config.

## Prerequisites

- Docker Desktop (or another Docker Engine + Compose v2) running locally.
- **JDK 21 (LTS)**, on `PATH`/`JAVA_HOME`, to build/run the Java services under
  `services/`/`platform/` alongside this stack. The root `pom.xml` pins
  `maven.compiler.release=21`; an older JDK (e.g. JDK 17, common on dev
  machines by default) fails the build outright with a compiler-release
  error. Check `java -version` before your first `mvn` build — see
  [`CLAUDE.md`](../CLAUDE.md#buildtest-maven-multi-module-monorepo) for the
  full build section, including a documented `winget` install trap on
  Windows (silent deadlock in headless/scripted sessions — use the portable
  Temurin 21 zip instead in CI or scripted setup).
- A `.env` file at the repo root, created from the template:

  ```
  cp .env.example .env
  ```

  Every value in `.env.example` is a placeholder — edit `.env` if you want
  different local credentials/ports. `.env` is gitignored; never commit it.

## Bringing the stack up

From the repo root:

```
docker compose up -d
```

This starts, in dependency order:

1. **postgres** (`postgres:16-alpine`) — one Postgres server, two isolated
   databases/roles:
   - `chattera` / `chattera` role (from `POSTGRES_DB` / `POSTGRES_USER`) —
     the application database, owned by the bootstrap superuser for dev
     convenience (Flyway migrations etc. need DDL rights).
   - `keycloak` / `keycloak` role (from `KEYCLOAK_DB_NAME` /
     `KEYCLOAK_DB_USER`) — created by
     [`infra/postgres/init/01-init-keycloak-db.sh`](postgres/init/01-init-keycloak-db.sh)
     the first time the data volume initializes. This role owns only the
     `keycloak` database and has **no** access to `chattera` — Keycloak
     never shares a schema or role with the application.
   - Published on `localhost:${POSTGRES_PORT:-5432}`.
2. **redis** (`redis:7-alpine`) — cache / presence keys. No named volume:
   presence keys expire by design and the hot-conversation cache is not
   meant to survive a restart, so ephemeral storage is intentional here.
   Published on `localhost:${REDIS_PORT:-6379}`.
3. **minio** (`minio/minio:latest`) — S3-compatible object storage backing
   the file service. Published on `localhost:${MINIO_API_PORT:-9000}` (S3
   API) and `localhost:${MINIO_CONSOLE_PORT:-9001}` (web console, login
   with `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`).
4. **keycloak** (`quay.io/keycloak/keycloak:26.0`, `start-dev
   --import-realm`) — waits for postgres to be healthy, connects to the
   separate `keycloak` database, and auto-imports
   [`infra/keycloak/realm-export/chattera-realm.json`](keycloak/realm-export/chattera-realm.json)
   on startup. Published on `localhost:${KEYCLOAK_PORT:-8080}`.

All container state (Postgres data, Keycloak's local runtime data, MinIO
objects) lives in named Docker volumes (`postgres_data`, `keycloak_data`,
`minio_data`), so it survives `docker compose down`. Use `docker compose
down -v` to wipe everything and start fresh (e.g. to re-trigger the Postgres
init script or reset the Keycloak realm to exactly what's in the JSON file).

> Note: Keycloak's actual source of truth (realm, clients, users) lives in
> the `keycloak` Postgres database, not the `keycloak_data` volume. That
> volume just persists Keycloak's own local runtime data (provider/theme
> cache, etc.) between restarts.

## Where things are

| Thing | URL / address | Credentials |
|---|---|---|
| Keycloak admin console | http://localhost:8080/admin | `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` from `.env` |
| Keycloak `chattera` realm OIDC discovery | http://localhost:8080/realms/chattera/.well-known/openid-configuration | n/a |
| Keycloak health/metrics (management interface) | http://localhost:9990/health/ready (mapped from container port 9000; see note below) | n/a |
| MinIO console | http://localhost:9001 | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` from `.env` |
| Postgres | localhost:5432, databases `chattera` and `keycloak` | see `.env` |
| Redis | localhost:6379 | none (dev only) |

Keycloak's management port (health `/health/ready`, `/health/live`, and
`/metrics`, enabled via `KC_HEALTH_ENABLED=true` / `KC_METRICS_ENABLED=true`)
is container-internal port 9000. It's mapped to **host port 9990** instead
of 9000 because MinIO's S3 API already uses host port 9000 — no functional
difference, just avoids a port collision on your machine.

## Verifying the realm imported correctly

Once `docker compose up -d` reports `keycloak` as healthy (`docker compose
ps`), confirm the realm and both clients exist by hitting the OIDC discovery
endpoint:

```
curl http://localhost:8080/realms/chattera/.well-known/openid-configuration
```

You should get back a JSON document with `"issuer":
"http://localhost:8080/realms/chattera"` and the realm's `authorization_endpoint`,
`token_endpoint`, `jwks_uri`, etc. If you get a 404, the realm didn't
import — check `docker compose logs keycloak` for import errors (a common
cause is a syntax error in `infra/keycloak/realm-export/chattera-realm.json`,
or the realm already existing from a previous run with the volume kept —
`--import-realm` skips realms that already exist, so `docker compose down
-v` and re-up if you need a clean re-import after editing the JSON).

To double check both clients are present without the admin console:

```
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD"
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh get clients -r chattera --fields clientId
```

## DEV/TEST ONLY: getting a token without a browser

The `chattera-web` and `chattera-mobile` clients are public clients doing
Authorization Code + PKCE — the *only* supported flow for real users, and
`directAccessGrantsEnabled: false` on both, by design. There is no frontend
yet, so there's no way to drive that browser-based flow headlessly.

For automated smoke testing (e.g. hitting `profile-service` with a real
bearer token from a script or CI job), the realm has a **separate,
dedicated, dev/test-only** client:

> **`chattera-test-client` must never be enabled, imported, or replicated in
> any staging or production realm.** It is a confidential client with
> `directAccessGrantsEnabled: true` (the Resource Owner Password Credentials
> grant — `grant_type=password`), which bypasses the browser login entirely.
> That's fine for a throwaway local Postgres-backed realm nobody else can
> reach; it is not an acceptable pattern anywhere real users' credentials
> flow through Keycloak. `chattera-web`/`chattera-mobile` keep
> `directAccessGrantsEnabled: false` — do not change that.

Both the client (`chattera-test-client`, confidential,
`directAccessGrantsEnabled: true`) and a matching test user (`testuser`) are
defined in
[`infra/keycloak/realm-export/chattera-realm.json`](keycloak/realm-export/chattera-realm.json),
each with an inline `description`/`attributes._comment` flagging them as
dev/test-only. Credentials are also mirrored in `.env.example`/`.env` (see
`KEYCLOAK_TEST_CLIENT_ID`, `KEYCLOAK_TEST_CLIENT_SECRET`,
`KEYCLOAK_TEST_USERNAME`, `KEYCLOAK_TEST_USER_PASSWORD`) purely for
discoverability — the realm JSON doesn't do env-var substitution, so those
`.env` values must stay in sync with the JSON by hand if either changes.

Get a token:

```bash
curl -s -X POST http://localhost:8080/realms/chattera/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=chattera-test-client" \
  -d "client_secret=29783f3965931971396b129def2e82eac45bc259527aaf89" \
  -d "username=testuser" \
  -d "password=186ba229b456666f3a76d3de" \
  -d "scope=openid"
```

Returns a standard OIDC token response (`access_token` is a signed RS256 JWT
usable as `Authorization: Bearer <access_token>` against any Chattera
resource server pointed at this realm, `expires_in: 300` matching the
realm's `accessTokenLifespan`). To pull out just the access token in a
script:

```bash
curl -s -X POST http://localhost:8080/realms/chattera/protocol/openid-connect/token \
  -d "grant_type=password" -d "client_id=chattera-test-client" \
  -d "client_secret=29783f3965931971396b129def2e82eac45bc259527aaf89" \
  -d "username=testuser" -d "password=186ba229b456666f3a76d3de" \
  -d "scope=openid" | jq -r .access_token
```

### Re-importing the realm after editing the JSON

`--import-realm` only imports on Keycloak's *first* boot against an empty
`keycloak` Postgres database — editing
`infra/keycloak/realm-export/chattera-realm.json` and restarting the
`keycloak` container alone does **nothing**, because Keycloak sees an
already-populated `keycloak` database and skips the import. Since Keycloak's
actual source of truth is that Postgres database (not the `keycloak_data`
volume — see note above), forcing a re-import means giving it back an empty
`keycloak` database, then restarting:

```bash
docker compose stop keycloak

# Drop and recreate just the `keycloak` database/role setup (leaves the
# `chattera` app database and its data completely untouched — this only
# repeats what infra/postgres/init/01-init-keycloak-db.sh does for the
# `keycloak` db, it does not touch `chattera`):
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'keycloak';"
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE keycloak;"
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE keycloak OWNER keycloak;"
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres -c "REVOKE ALL PRIVILEGES ON DATABASE keycloak FROM PUBLIC;"
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d keycloak -c "GRANT ALL PRIVILEGES ON SCHEMA public TO keycloak;"

docker compose start keycloak
```

This is the scoped alternative to the `docker compose down -v` sledgehammer
mentioned above — use `down -v` if you don't care about losing Postgres/MinIO
data too, use the drop/recreate sequence above if you do (e.g. once
`chattera` actually has app data in it you want to keep).

A gotcha hit while building this: Keycloak's realm import runs inside a
single DB transaction/batch per entity type, and Postgres silently caps
`CLIENT.DESCRIPTION` (and similar free-text columns) at `varchar(255)` — a
too-long `description` on a client fails the whole import with `ERROR:
value too long for type character varying(255)` and Keycloak refuses to
start. Keep client/user `description` fields short.

## Pointing a Chattera service at this Keycloak instance

Per `docs/solution-architecture.md`, every Chattera service is an OAuth2
resource server. For local dev, point `issuer-uri` at:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/chattera
```

Spring Security resolves `/.well-known/openid-configuration` and the JWKS
from that issuer automatically — no separate JWKS URI needed. This works
from a service running directly on the host (not itself in the compose
network) because Keycloak's `KC_HOSTNAME`/issuer defaults to what the client
used to reach it, and in `start-dev` mode hostname checks are relaxed. If a
service is later containerized and joins the compose network, it should
reach Keycloak by service name (`http://keycloak:8080`) instead of
`localhost` — note that the issuer claim embedded in tokens will then differ
by network context; revisit `KC_HOSTNAME` if that becomes a problem.

## Stopping / resetting

```
docker compose down        # stop containers, keep volumes (state persists)
docker compose down -v     # stop containers AND remove volumes (full reset)
```

## Known deviations / follow-ups (see devops-engineer report for full detail)

- No `set-client-secrets.sh` bootstrap script: both `chattera-web` and
  `chattera-mobile` are public clients (Authorization Code + PKCE, no client
  secret), so there's no secret to provision. Deferred until a confidential
  client is actually introduced (e.g. a BFF or a service needing the
  Keycloak Admin API).
- The Keycloak container image doesn't ship `curl`, so its Docker
  healthcheck uses a bash `/dev/tcp` probe against the management port
  instead of a `curl`-based one (see `docker-compose.yml`). Functionally
  equivalent to `curl -f http://localhost:9000/health/ready`.
- **Host port conflicts**: `MINIO_API_PORT` defaults to `9000`, but that
  port is commonly already bound on corporate Windows machines by an
  always-on VPN/proxy agent (e.g. Zscaler's `ZSATunnel.exe`) — `docker
  compose up -d` will fail with `ports are not available` in that case. Fix
  is local-only: pick a different `MINIO_API_PORT` in your own `.env` (e.g.
  `9002`); no change needed to `.env.example` or `docker-compose.yml`.
- **Known gap, not yet hardened**: `infra/postgres/init/01-init-keycloak-db.sh`
  revokes all privileges on the `keycloak` database from `PUBLIC` and never
  grants the `chattera` role anything on it, so the `keycloak` role
  genuinely cannot read/write any object in the `chattera` database (schema
  `CREATE`/object grants are separate from `CONNECT`, and Postgres 15+
  doesn't grant `CREATE ON SCHEMA public` to `PUBLIC` by default — verified:
  `psql -U keycloak -d chattera` connects but `CREATE TABLE` in it fails
  with `permission denied for schema public`). However, the `keycloak` role
  is *not* explicitly denied `CONNECT` on the `chattera` database itself (a
  session can be opened, it just can't do anything once connected, and
  there's nothing to read yet). For stricter isolation, add `REVOKE CONNECT
  ON DATABASE chattera FROM PUBLIC;` (and an explicit `GRANT CONNECT ...
  TO "chattera"`) to the init script. Not applied yet — flagging rather than
  changing silently, since it also means updating the app role's own grants,
  and there's no `chattera` app data yet for this to matter practically.
