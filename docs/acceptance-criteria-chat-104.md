# Acceptance Criteria — CHAT-104 (Chat Rooms & Messaging, FR-02)

> **RETROACTIVE / BACKFILLED — read before using these.**
> CHAT-104 was implemented, code-reviewed, bug-fixed, and QA-validated (25 passing smoke
> tests, ticket status Done, live Jira key CHAT-5) **before** any acceptance criteria were
> independently drafted. Implementation proceeded directly from
> [`docs/solution-architecture.md`](solution-architecture.md)'s "Chat Rooms & Messaging
> (CHAT-104 / FR-02)" section, and QA's smoke tests were derived from that same design doc
> rather than from ACs written ahead of the build.
>
> The ACs below were written **after the fact**, by reading the shipped
> `chat-service` code (`RoomController`, `MessageController`, `RoomService`,
> `MessageService`, `RoomAccessService`, the exception hierarchy under
> `service/exception/`) and cross-checking it against `solution-architecture.md` and
> `functional-requirements.md` FR-02. They describe and validate **already-shipped
> behavior** — they did not drive the build, and should not be cited as if they did. Their
> purpose is to close the process gap flagged by qa-engineer and an earlier refinement
> session: give future regression coverage (and any CHAT-104 change) a real spec to test
> against instead of "whatever the code currently does."
>
> Authored: 2026-07-25. References FR-02 (`docs/functional-requirements.md`) and the
> "Chat Rooms & Messaging (CHAT-104 / FR-02)" section of `docs/solution-architecture.md`.

## Where this lives / naming convention note
No repo-committed AC doc could be found for CHAT-105 (one-to-one DMs) to mirror the
convention from. Per the standing project memory, CHAT-105's 20 ACs were authored directly
in the live Jira issue description (with 3 still unconfirmed — AC-4, AC-10, AC-18 — pending
solution-architect/PM sign-off), not checked into `docs/`. This doc is therefore a **new**
location, not a continuation of an existing one. Recommend two follow-ups (scrum-master /
process, not executed here):
1. Mirror this doc's content into the live CHAT-104/CHAT-5 Jira issue description, for
   parity with how CHAT-105 is tracked.
2. Decide going forward whether ACs live in Jira, in `docs/`, or both — right now the two
   tickets use different conventions, which is itself worth flagging.

Also note: `docs/jira-sprint-board.md` currently still lists CHAT-104 under "To Do" with no
Done marker or smoke-test reference, which is stale relative to its actual state (Done,
CHAT-5, 25 passing smoke tests). Not corrected here — that file is scrum-master's owned
artifact — but flagged so it doesn't get missed.

---

## Room Creation — `POST /rooms`

**AC-1 — Create a PUBLIC room**
- Given an authenticated user with a valid Keycloak-issued JWT
- When they `POST /rooms` with `{ "name": "<1-255 chars>", "type": "PUBLIC" }`
- Then the response is `201 Created` with the created room (`id`, `name`, `type`,
  `createdBy` = caller's `sub`, `createdAt`, `member: true`, `role: "OWNER"`)
- And the caller is persisted as a `room_members` row with `role = OWNER`

**AC-2 — Create a PRIVATE room**
- Given an authenticated user
- When they `POST /rooms` with `{ "name": "<1-255 chars>", "type": "PRIVATE" }`
- Then the response is `201 Created`, identical shape to AC-1, `type: "PRIVATE"`
- And the room is **not** visible or self-joinable to other users (see AC-6, AC-9)

**AC-3 — Creator always becomes OWNER**
- Given any successful room creation (PUBLIC or PRIVATE)
- Then exactly one `room_members` row is created in the same transaction as the room, for
  the creator, with `role = OWNER`, `joined_at` set
- And no other members exist on the room immediately after creation

**AC-4 — Blank/missing room name is rejected**
- Given an authenticated user
- When they `POST /rooms` with `name` blank, whitespace-only, missing, or over 255 chars
- Then the response is `400 Bad Request` with `code: "VALIDATION_ERROR"`
- And no room row is created

**AC-5 — Missing/invalid `type` is rejected**
- Given an authenticated user
- When they `POST /rooms` with `type` missing or not one of `PUBLIC`/`PRIVATE`/`DIRECT`
- Then the response is `400 Bad Request` with `code: "VALIDATION_ERROR"`

**AC-6 — DIRECT rooms cannot be created via this endpoint**
- Given an authenticated user
- When they `POST /rooms` with `type: "DIRECT"`
- Then the response is `400 Bad Request` with `code: "UNSUPPORTED_ROOM_TYPE"`
- And no room row is created
- Note: DIRECT-room creation is exclusively CHAT-105's find-or-create-DM flow; this is a
  deliberate boundary, not a bug. Flagging here because it's a real, testable rejection
  path a QA case could easily miss.

**AC-7 — Room creation requires authentication**
- Given no `Authorization` header, or an expired/invalid/malformed bearer token
- When `POST /rooms` is called
- Then the response is `401 Unauthorized` and no room is created

---

## Room Listing — `GET /rooms`

**AC-8 — Listing returns caller's memberships plus all PUBLIC rooms**
- Given room A (PUBLIC, caller is not a member), room B (PUBLIC, caller is a member), room
  C (PRIVATE, caller is a member), room D (PRIVATE, caller is **not** a member)
- When the caller calls `GET /rooms`
- Then the response includes A, B, and C, but **not** D
- This is a documented product decision (there is no separate "browse public rooms"
  endpoint in Sprint 1 scope), not an oversight — flagging so it's looked-up, not
  re-derived, next time someone questions why a PRIVATE room they're not in is invisible.

**AC-9 — Each listed room reports the caller's own membership/role**
- Given the four rooms from AC-8
- When the caller calls `GET /rooms`
- Then room A (not a member) has `member: false`, `role: null`
- And rooms B and C (member) have `member: true`, `role` = the caller's actual role
  (`OWNER` or `MEMBER`)

**AC-10 — Listing requires authentication**
- Given no/invalid token
- When `GET /rooms` is called
- Then the response is `401 Unauthorized`

**AC-11 (flag, not a defect) — No pagination on `GET /rooms`**
- The endpoint returns the caller's full membership set plus *every* PUBLIC room in the
  system in one response, with no `limit`/cursor. Fine at Sprint 1 scale; flag as a
  follow-up if/when PUBLIC-room count grows — not an AC failure today, just recorded so
  it isn't silently assumed to already have pagination.

---

## Joining a Room — `POST /rooms/{roomId}/join`

**AC-12 — Any authenticated user can join a PUBLIC room**
- Given a PUBLIC room the caller is not yet a member of
- When they `POST /rooms/{roomId}/join`
- Then the response is `200 OK` with the room, `member: true`, `role: "MEMBER"`
- And a `room_members` row is created for the caller with `role = MEMBER`

**AC-13 — Join is idempotent for an existing member**
- Given a PUBLIC room the caller is already a member of (including the OWNER)
- When they `POST /rooms/{roomId}/join` again
- Then the response is `200 OK` with their existing role unchanged (no duplicate row, no
  error)

**AC-14 — PRIVATE and DIRECT rooms are not self-joinable**
- Given a PRIVATE room (or a DIRECT room) the caller is not a member of
- When they `POST /rooms/{roomId}/join`
- Then the response is `403 Forbidden` with `code: "ROOM_NOT_SELF_JOINABLE"`
- And no membership row is created
- Note: membership in PRIVATE rooms is currently established only by an owner-adds-member
  action; there is **no owner-invite/add-member endpoint shipped in CHAT-104**. This means
  a PRIVATE room, once created, has no shipped path to add a second member yet — that's a
  real functional gap worth its own follow-up ticket (invite/add-member workflow),
  explicitly noted as post-Sprint-1 in `solution-architecture.md`.

**AC-15 — Joining a nonexistent room**
- Given a `roomId` that does not exist
- When `POST /rooms/{roomId}/join` is called
- Then the response is `404 Not Found` with `code: "ROOM_NOT_FOUND"`

**AC-16 — Join requires authentication**
- Given no/invalid token
- When `POST /rooms/{roomId}/join` is called
- Then the response is `401 Unauthorized`

---

## Leaving a Room — `POST /rooms/{roomId}/leave`

**AC-17 — A regular member can leave**
- Given a room with an OWNER and at least one other MEMBER
- When the MEMBER calls `POST /rooms/{roomId}/leave`
- Then the response is `204 No Content`
- And their `room_members` row is deleted; the room, its OWNER, and its message history
  are unaffected

**AC-18 — OWNER leaving transfers ownership to the oldest remaining member**
- Given a room with an OWNER and two or more other MEMBERs with distinct `joined_at`
  timestamps
- When the OWNER calls `POST /rooms/{roomId}/leave`
- Then the response is `204 No Content`
- And the OWNER's membership row is deleted
- And the remaining member with the earliest `joined_at` is promoted to `role = OWNER`
- And all other remaining members keep `role = MEMBER`
- This is a deliberate product decision (auto-transfer, not "block the OWNER from
  leaving") — record it here explicitly since it's not derivable from FR-02's one-line
  bullet and is easy to get wrong in a regression.

**AC-19 — Last member leaving does not delete the room**
- Given a room with exactly one remaining member (regardless of role)
- When that member calls `POST /rooms/{roomId}/leave`
- Then the response is `204 No Content`
- And the room row still exists afterward (confirmable via direct query — it is no longer
  visible to the departed user via `GET /rooms`, since they're neither a member nor is a
  PRIVATE room PUBLIC)
- And the room has zero members
- And the room's message history remains intact and fetchable by anyone who later
  re-joins/is re-added (for PUBLIC: self-rejoin via AC-12; for PRIVATE: only via a future
  add-member path, see AC-14's flagged gap)
- This is a deliberate product decision (rooms are never auto-deleted) — same rationale as
  AC-18: worth an explicit, look-up-able AC rather than leaving it as an inference from
  code.

**AC-20 — Leaving a room the caller is not a member of**
- Given a room that exists but the caller is not a member of
- When they call `POST /rooms/{roomId}/leave`
- Then the response is `403 Forbidden` with `code: "NOT_ROOM_MEMBER"`

**AC-21 — Leaving a nonexistent room**
- Given a `roomId` that does not exist
- When `POST /rooms/{roomId}/leave` is called
- Then the response is `404 Not Found` with `code: "ROOM_NOT_FOUND"`

**AC-22 — Leave requires authentication**
- Given no/invalid token
- When `POST /rooms/{roomId}/leave` is called
- Then the response is `401 Unauthorized`

---

## Posting a Message — `POST /rooms/{roomId}/messages`

**AC-23 — A member can post a message**
- Given the caller is a member (any role) of an existing room
- When they `POST /rooms/{roomId}/messages` with `{ "content": "<1-4000 chars>" }`
- Then the response is `201 Created` with the persisted message (`id`, `roomId`,
  `senderId` = caller's `sub`, `content`, `status: "SENT"`, `createdAt`)
- And the message row is committed to Postgres before the response is returned

**AC-24 — A non-member cannot post**
- Given a room that exists but the caller is not a member of
- When they `POST /rooms/{roomId}/messages`
- Then the response is `403 Forbidden` with `code: "NOT_ROOM_MEMBER"`
- And no message row is created

**AC-25 — Posting to a nonexistent room**
- Given a `roomId` that does not exist
- When `POST /rooms/{roomId}/messages` is called
- Then the response is `404 Not Found` with `code: "ROOM_NOT_FOUND"`
- Non-member and nonexistent-room checks are ordered so a caller reliably gets 404 for an
  unknown room rather than a misleading 403 — worth an explicit test since the ordering is
  a deliberate implementation choice, not incidental.

**AC-26 — Blank message content is rejected**
- Given a member posting to an existing room
- When `content` is blank, whitespace-only, or missing
- Then the response is `400 Bad Request` with `code: "VALIDATION_ERROR"`
- And no message row is created

**AC-27 — Oversized message content is rejected**
- Given a member posting to an existing room
- When `content` exceeds 4000 characters
- Then the response is `400 Bad Request` with `code: "VALIDATION_ERROR"`
- And no message row is created
- (4000 is a pragmatic engineering bound, not a product-specified limit — flagging in case
  product/BA wants an explicit, intentionally-chosen max message length as a follow-up
  decision rather than an implementation default.)

**AC-28 — Posted messages are always `status: SENT`, never `DELIVERED`/`READ`**
- Given any successfully posted message
- Then its persisted/returned `status` is always `"SENT"`
- And CHAT-104 never transitions a message to `DELIVERED` or `READ` — those transitions
  are explicitly CHAT-107's responsibility (real-time delivery/read-receipt round trip),
  out of CHAT-104 scope
- This boundary is intentional per `solution-architecture.md` and should not be treated as
  a missing feature of CHAT-104.

**AC-29 — Persist-then-publish; publish failure never fails the write**
- Given a member successfully posts a message
- When the message row commits to Postgres
- Then a `RoomMessageCreatedEvent` is published to RabbitMQ (topic exchange, routed by
  room) **only after** that commit (`AFTER_COMMIT` phase) — never inside the same
  transaction, and never before the commit
- And if the RabbitMQ publish fails or no consumer/queue is bound yet (e.g. CHAT-107 not
  deployed), the REST call still returns `201 Created` with the persisted message — a
  publish failure is logged but must not surface as an error to the caller, and must not
  roll back the message row
- This is verified entirely over REST per the CHAT-104/CHAT-107 scope split (POST returns
  the persisted message; no WebSocket dependency required to validate this ticket) —
  actual delivery-to-socket is explicitly out of scope for CHAT-104 and belongs to
  CHAT-107's test suite, not this one.

**AC-30 — Posting requires authentication**
- Given no/invalid token
- When `POST /rooms/{roomId}/messages` is called
- Then the response is `401 Unauthorized`

---

## Fetching Message History — `GET /rooms/{roomId}/messages`

**AC-31 — A member can fetch history, newest-first**
- Given a room with N persisted messages and the caller is a member
- When they `GET /rooms/{roomId}/messages` with no query params
- Then the response is `200 OK` with up to 50 messages ordered newest-first
  (`created_at DESC, id DESC`)

**AC-32 — Default and maximum page size is 50**
- Given a room with more than 50 messages
- When the caller fetches history with no `limit` param
- Then exactly 50 messages are returned (the default) plus a non-null `nextCursor`
  indicating more history exists

**AC-33 — Requesting more than 50 clamps to 50, does not error**
- Given a room with more than 50 messages
- When the caller fetches history with `limit=200` (or any value > 50)
- Then the response is `200 OK` (not a validation error) with exactly 50 messages returned
  — the limit is silently clamped to the hard max, per the documented pagination design
  ("a bound is mandatory regardless — an unbounded response on a busy room is a
  correctness/performance defect, not a missing feature")

**AC-34 — `limit` below 1 clamps to 1, does not error**
- Given a room with at least 1 message
- When the caller fetches history with `limit=0` or a negative value
- Then the response is `200 OK` with exactly 1 message returned (clamped, not rejected)
- Flagging this explicitly since "clamp silently" vs. "400 on an out-of-range limit" is a
  real design choice a tester could reasonably expect to go the other way — worth having
  on record rather than surprising QA.

**AC-35 — `before` cursor loads older messages**
- Given a room with more than 50 messages, and a first page fetched with a returned
  `nextCursor`
- When the caller fetches `GET /rooms/{roomId}/messages?before=<nextCursor>`
- Then the response contains the next-older page of messages (no overlap with the first
  page), still newest-first within that page
- And when the oldest message has been reached, `nextCursor` in the response is `null`

**AC-36 — History requires membership**
- Given a room that exists but the caller is not a member of
- When they `GET /rooms/{roomId}/messages`
- Then the response is `403 Forbidden` with `code: "NOT_ROOM_MEMBER"`

**AC-37 — History for a nonexistent room**
- Given a `roomId` that does not exist
- When `GET /rooms/{roomId}/messages` is called
- Then the response is `404 Not Found` with `code: "ROOM_NOT_FOUND"`

**AC-38 — History requires authentication**
- Given no/invalid token
- When `GET /rooms/{roomId}/messages` is called
- Then the response is `401 Unauthorized`

---

## Cross-Cutting: Identity / Authorization

**AC-39 — `userId` is always server-derived, never client-supplied**
- Given any CHAT-104 endpoint (create/list/join/leave/post/history)
- When the caller is authenticated
- Then the acting `userId` used for authorship, membership checks, and ownership is always
  the `sub` claim of the validated JWT — there is no request field (body, query param, or
  header) anywhere in the CHAT-104 API surface that lets a caller assert a different
  `userId`
- This should be spot-checked by QA (e.g. attempt to pass a `userId`/`senderId` field in a
  POST body and confirm it's ignored) since it's a security-relevant guarantee, not just a
  convenience.

---

## FR-02 coverage assessment

FR-02 states: *"Users must be able to create, join, leave, and send messages in chat
rooms. Chat rooms must support multiple participants. Room participants must see message
history."*

- **Create/join/leave/send**: fully covered by AC-1 through AC-30 above. **Satisfied**,
  with the one caveat flagged in AC-14 (PRIVATE rooms have no shipped add-member path yet
  — a real gap, not just a documentation nuance, recommend a follow-up ticket such as
  "CHAT-1xx: owner add-member endpoint for PRIVATE rooms").
- **Message history**: fully covered by AC-31 through AC-38. **Satisfied**.
- **"Multiple participants"**: technically satisfied — nothing prevents more than two
  members joining a PUBLIC room (AC-12, repeatable per user). **However, there is no
  documented or enforced cap on room membership size anywhere in the shipped code or in
  `solution-architecture.md`.** This is worth flagging explicitly rather than silently
  treating "no cap" as fine:
  - It may be an entirely intentional Sprint-1 simplification (unbounded is simpler, and a
    real cap is a product/scale decision, not an engineering default to invent
    unilaterally).
  - But an unbounded room is also a potential abuse/DoS vector (e.g. nothing stops one
    user scripting thousands of joins to a PUBLIC room) and a future scale concern
    (`solution-architecture.md`'s "Known limits" section already flags message-volume and
    live-subscriber fanout as deferred scale problems; room membership size is adjacent
    but not explicitly named there).
  - **Recommendation**: this needs an explicit product/PM decision, not a BA-invented
    number — flagging for scrum-master/project-manager as a candidate follow-up ticket
    ("define and enforce a max room membership size, or explicitly ratify 'no cap in
    Sprint 1' as the intentional decision") rather than writing a testable AC for a limit
    that doesn't exist and wasn't decided.

No conflicts found between CHAT-104's shipped behavior and FR-05 (real-time delivery) or
FR-03 (DMs) — the `status: SENT`-only behavior (AC-28) and REST-write/WebSocket-receive
split are consistent with the documented CHAT-104/CHAT-107 and CHAT-104/CHAT-105
boundaries in `solution-architecture.md`, not a contradiction of FR-05's real-time
requirement (that requirement is owned by CHAT-107, not yet built).
