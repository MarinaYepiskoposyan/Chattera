# Chattera Dev Console

A throwaway dev-convenience tool, **not** a product frontend. It exists so you
can see the backend (profile-service, chat-service) working end-to-end from a
browser instead of hand-writing curl commands. No build step, no npm, no
framework — plain static HTML + vanilla JS + minimal CSS, served directly.

Do not add product features here, and do not treat it as the starting point
for the real Chattera web client.

## Prerequisites

- The local infra stack up: `docker compose up -d` from the repo root
  (Postgres, Redis, Keycloak, RabbitMQ — see `infra/README.md`).
- `profile-service` running on port 8082 and `chat-service` running on port
  8083 (`mvn spring-boot:run` from each service directory — see the root
  `CLAUDE.md` "Build/test" section).

## Running it

This page **must** be served on `http://localhost:3000` — that's the only
origin registered as a redirect URI / web origin for the `chattera-web`
Keycloak client (see
`infra/keycloak/realm-export/chattera-realm.json`). Opening `index.html`
directly via `file://`, or serving it on a different port, will fail the
Keycloak login redirect.

From this directory (`dev-tools/web-console`), any of these work — pick
whichever you already have installed:

```
python -m http.server 3000
```

or

```
npx serve -p 3000
```

Then open http://localhost:3000 in a browser.

## Using it

1. Click **Log in** — you're redirected to Keycloak's real login page.
   Log in with a `chattera` realm user (e.g. the `testuser` dev account
   documented in `infra/README.md`; despite existing primarily for the
   headless Resource Owner Password Credentials smoke-test flow, it's a
   normal Keycloak user and works fine through a real browser login too).
2. You're redirected back to this page with an authorization `code`, which
   is exchanged (with the PKCE `code_verifier`) for an access token. The
   token is kept in `sessionStorage` only (cleared when the tab closes) and
   attached as `Authorization: Bearer <token>` on every API call below.
3. **Profile** panel loads your profile (`GET /me` on profile-service,
   JIT-provisioned on first call) and lets you update
   displayName/avatarUrl/timezone (`PUT /me`).
4. **Rooms** panel lists rooms you can see (`GET /rooms` on chat-service),
   lets you create one (`POST /rooms`), and join/leave (`POST
   /rooms/{id}/join` / `/leave`). Click a room's name to open it and see/post
   messages (`GET`/`POST /rooms/{roomId}/messages`).
5. Errors (4xx/5xx from either service) are shown inline, including the raw
   `code`/`message` from the API — that's intentional for a dev tool.
6. **Log out** just clears the local token; it does not end the Keycloak SSO
   session server-side.

## Notes / limitations

- No token refresh: the Keycloak `chattera` realm's access tokens are
  short-lived (5 minutes). When one expires, the next API call gets a 401
  and the console drops back to "logged out" — just log in again.
- No pagination UI for message history (always shows the first/most recent
  page returned by `GET /rooms/{roomId}/messages`).
- No file upload/download UI (file-service is a scaffold only — see root
  `CLAUDE.md`).
