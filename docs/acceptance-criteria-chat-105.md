# Acceptance Criteria — CHAT-105 (One-to-One Direct Messaging, FR-03)

> **Written pre-implementation — describes target behavior, not verified-passing behavior.**
> `RoomService.createRoom` currently explicitly rejects `type: "DIRECT"` with
> `UnsupportedRoomTypeException` (see the comment at that throw site: "DIRECT rooms are not
> created via this endpoint (see CHAT-105)"). No find-or-create-DM endpoint exists yet in
> `RoomController` or any other controller. These ACs are written against
> [`docs/solution-architecture.md`](solution-architecture.md)'s "Chat Rooms & Messaging
> (CHAT-104 / FR-02)" section — specifically the "DMs (CHAT-105)" design notes under "Data
> model" — and against [FR-03](functional-requirements.md#fr-03-one-to-one-chat), to give
> developer a real spec to build against and QA a real spec to derive test cases from,
> rather than reverse-engineering ACs after the fact the way
> [`docs/acceptance-criteria-chat-104.md`](acceptance-criteria-chat-104.md) had to.
>
> Authored: 2026-08-16.

## Correction to a prior false claim (read once, then ignore)
A past session's notes (`docs/jira-sprint-board.md`'s CHAT-105 "To Do" entry, and this
file's own prior intro paragraph) claimed CHAT-105 already had 20 ACs "authored directly in
the live Jira CHAT-6 issue description," with 3 flagged unconfirmed (AC-4, AC-10, AC-18)
pending solution-architect/PM sign-off. This was checked directly against the real Jira
CHAT-6 issue (description, comments, attachments) this session and found to be false — the
issue contains nothing but its one-line summary ("Allow users to start private conversations
and exchange messages"). No such 20-item AC set exists anywhere in this repo either. There
was therefore nothing to "resolve" — the ACs below are drafted fresh. If any AC number below
happens to coincide with AC-4/AC-10/AC-18, that is coincidence, not continuity; those numbers
carry no prior meaning. `docs/jira-sprint-board.md`'s CHAT-105 entry is scrum-master's owned
artifact and is not corrected here, but is now known-stale and should be updated to point at
this file instead of the "20 ACs / 3 unconfirmed" claim.

---

## Design baseline these ACs assume (decided already, not re-derived here)
Per `solution-architecture.md`, a DM is **not** a separate schema — it is a `rooms` row with:
- `type = 'DIRECT'`, `name = NULL` (UI resolves the other participant's display name
  client-side, e.g. via `GET /rooms/{roomId}/members` + profile-service).
- Exactly two members, both `role = MEMBER` (ownership is meaningless for a DM), inserted
  atomically at creation — not self-joinable via `POST /rooms/{roomId}/join` (already
  enforced today per CHAT-104 AC-14, since that check is `type != PUBLIC`, not
  `PRIVATE`-specific).
- A `direct_key` column (DIRECT rows only) = the two `sub`s in canonical sorted order
  (`min(a,b) + ':' + max(a,b)`), `UNIQUE`, enforcing one DM room per user pair at the DB
  level.
- Find-or-create semantics: an endpoint that returns the existing DIRECT room for a pair if
  one exists, else creates it. All messaging/history after that goes through the same
  `/rooms/{roomId}/messages` endpoints CHAT-104 already ships — no DM-specific message
  endpoints.
- Self-DM is rejected.

**Endpoint contract assumed by these ACs (proposed, not yet ratified — see flagged items):**
`POST /rooms/direct` with body `{ "userId": "<target Keycloak sub>" }`, returning a
`RoomResponse` shaped identically to `POST /rooms`'s response (`id`, `name: null`, `type:
"DIRECT"`, `createdBy`, `createdAt`, `member: true`, `role: "MEMBER"`). This mirrors the
`/rooms` collection convention already established by CHAT-104 rather than a `/dms` or
`/rooms/{roomId}/dm` shape. **This exact route/verb/status-code split is an API-contract
call, flagged for solution-architect confirmation below — not something this document
should be read as having settled unilaterally.**

---

## Find-or-create a DM — `POST /rooms/direct`

**AC-1 — First call creates a new DIRECT room**
- Given caller A (authenticated, `sub = subA`) has no existing DM with user B (`sub = subB`)
- When A calls `POST /rooms/direct` with `{ "userId": "subB" }`
- Then the response is `201 Created` with a room: `type: "DIRECT"`, `name: null`,
  `member: true`, `role: "MEMBER"`
- And exactly two `room_members` rows exist for the room: `(subA, MEMBER)` and
  `(subB, MEMBER)` — both inserted in the same transaction as the room row
- And the room's `direct_key` is `min(subA,subB) + ":" + max(subA,subB)`

**AC-2 — Repeat find-or-create by the initiator is idempotent**
- Given A already has a DIRECT room with B (from AC-1)
- When A calls `POST /rooms/direct` with `{ "userId": "subB" }` again
- Then the response is `200 OK` (not `201` — no new room created) with the **same** `id` as
  AC-1's room
- And no second `room_members` or `rooms` row is created

**AC-3 — Repeat find-or-create by the other participant is order-independent**
- Given A already has a DIRECT room with B (from AC-1)
- When B (not A) calls `POST /rooms/direct` with `{ "userId": "subA" }`
- Then the response is `200 OK` with the **same** `id` as AC-1's room (`direct_key` is
  canonically sorted, so it doesn't matter who initiates or in which argument order)
- This is worth its own explicit AC rather than assuming AC-2 covers it — "idempotent for
  the same caller" and "symmetric regardless of who calls first" are different guarantees
  and a naive implementation (e.g. keying only on `created_by` + target) could pass AC-2 and
  silently fail AC-3.

**AC-4 — Self-DM is rejected**
- Given an authenticated caller A
- When A calls `POST /rooms/direct` with `{ "userId": "subA" }` (their own `sub`)
- Then the response is `400 Bad Request` with `code: "SELF_DM_NOT_ALLOWED"`
- And no room row is created

**AC-5 — Missing/blank target `userId` is rejected**
- Given an authenticated caller
- When they `POST /rooms/direct` with `userId` blank, whitespace-only, or missing
- Then the response is `400 Bad Request` with `code: "VALIDATION_ERROR"`
- And no room row is created

**AC-6 — No cross-service validation that the target user exists (flag, not a defect)**
- Given an authenticated caller A and a syntactically well-formed but non-existent
  `userId` (`subX`, no matching Keycloak account, no profile-service row)
- When A calls `POST /rooms/direct` with `{ "userId": "subX" }`
- Then the response is `201 Created` — the room and both membership rows are created exactly
  as in AC-1, with no check that `subX` corresponds to a real user
- This is a deliberate consistency decision, not an oversight: CHAT-104 has **no**
  cross-service user-existence validation anywhere (room membership, message `senderId`,
  etc. all accept any syntactically valid `sub` without calling profile-service), so CHAT-105
  follows the same precedent rather than inventing a new validation rule unilaterally. Flagged
  because it does mean a caller can create a DM "with" a `userId` that turns out to be
  garbage or a never-registered sub, and get a room with a permanently unreachable second
  participant. Recommend confirming this is acceptable for Sprint 1 (candidate follow-up:
  validate against profile-service, or at minimum against "has this `sub` ever authenticated
  against chat-service before") rather than silently carrying it forward as unexamined.

**AC-7 — DIRECT rooms are never creatable via `POST /rooms`**
- Cross-reference: already covered and shipped — CHAT-104 AC-6
  (`docs/acceptance-criteria-chat-104.md`) confirms `POST /rooms` with `type: "DIRECT"`
  returns `400 UNSUPPORTED_ROOM_TYPE`. Not re-tested here; listed so a reader of this doc
  doesn't go looking for a duplicate AC.

**AC-8 — DIRECT rooms are not self-joinable via `POST /rooms/{roomId}/join`**
- Given a DIRECT room that exists (from AC-1) and a third user C who is not one of its two
  members
- When C calls `POST /rooms/{roomId}/join`
- Then the response is `403 Forbidden` with `code: "ROOM_NOT_SELF_JOINABLE"`
- This is CHAT-104 AC-14's existing `type != PUBLIC` check, which already covers `DIRECT`
  with zero new code — confirmed here as an explicit CHAT-105 regression case, since it's
  easy to assume (wrongly) that DIRECT needs its own new join-rejection path.

**AC-9 — Find-or-create requires authentication**
- Given no `Authorization` header, or an expired/invalid/malformed bearer token
- When `POST /rooms/direct` is called
- Then the response is `401 Unauthorized` and no room is created

---

## Messaging in a DM room — reuse of `/rooms/{roomId}/messages`

**AC-10 — A DM participant can post a message**
- Given a DIRECT room with members A and B (from AC-1)
- When A calls `POST /rooms/{roomId}/messages` with `{ "content": "<1-4000 chars>" }`
- Then the response is `201 Created` with the persisted message (`senderId: "subA"`,
  `status: "SENT"`), identical shape and behavior to CHAT-104 AC-23 — no DM-specific message
  endpoint, request, or response shape exists or is needed

**AC-11 — A DM participant can fetch message history**
- Given a DIRECT room with more than 50 messages between A and B
- When either A or B calls `GET /rooms/{roomId}/messages`
- Then the response behaves identically to CHAT-104 AC-31 through AC-35 (50 default/max page
  size, newest-first, `before`-cursor keyset pagination) — no separate pagination behavior
  for DMs

**AC-12 — A non-participant cannot post to or read a DM room**
- Given a DIRECT room with members A and B, and a third authenticated user C
- When C calls `POST /rooms/{roomId}/messages` or `GET /rooms/{roomId}/messages`
- Then the response is `403 Forbidden` with `code: "NOT_ROOM_MEMBER"` for either call —
  identical to CHAT-104 AC-24/AC-36, with no special-casing for `DIRECT` vs. other room
  types (the membership check in `RoomAccessService` is type-agnostic)

**AC-13 — Posted DM messages follow the same persist-then-publish and status rules**
- Cross-reference: CHAT-104 AC-28 (`status` always `SENT` at write time,
  `DELIVERED`/`READ` are CHAT-107's responsibility) and AC-29 (publish to Kafka only after
  DB commit, publish failure never fails the write) apply unchanged to DM-room messages —
  `MessageService` has no `RoomType` branch, so this needs no new implementation and no new
  test beyond confirming it against a DIRECT `roomId` instead of a PUBLIC/PRIVATE one

---

## Visibility / listing

**AC-14 — DM rooms appear in `GET /rooms` for participants only, with `name: null`**
- Given a DIRECT room between A and B
- When A calls `GET /rooms`
- Then the response includes the DM room with `name: null`, `type: "DIRECT"`,
  `member: true`, `role: "MEMBER"`
- When a third user C (not a participant) calls `GET /rooms`
- Then the response does **not** include the DM room (DIRECT rooms are never listed as
  "every PUBLIC room" the way CHAT-104 AC-8 lists PUBLIC rooms to non-members — this is
  already the existing `listVisibleRooms` behavior, since it only auto-includes PUBLIC rooms
  plus the caller's own memberships)

**AC-15 — `GET /rooms/{roomId}/members` resolves DM participant identity (no new endpoint needed)**
- Given a DIRECT room between A and B
- When A calls `GET /rooms/{roomId}/members`
- Then the response lists both `{ userId: "subA", role: "MEMBER" }` and
  `{ userId: "subB", role: "MEMBER" }`
- This is the mechanism a client uses to resolve "the other participant" and look up their
  display name (via profile-service) for the DM UI, per `solution-architecture.md`'s "the UI
  shows the other participant's display name" note — the existing CHAT-107
  `GET /rooms/{roomId}/members` endpoint (`RoomMemberController`) already serves this with
  zero new work; flagging explicitly so it isn't mistaken for a gap needing a new endpoint

---

## Real-time delivery interaction (CHAT-107, already implemented)

**AC-16 — DM messages are delivered in real time via the same mechanism as room messages**
- Given A and B both have live WebSocket connections subscribed to the DM room's STOMP
  destination
- When A posts a message via `POST /rooms/{roomId}/messages`
- Then B receives the message over the socket via the exact same event
  (`RoomMessageCreatedEvent`), topic pattern, and subscribe-time membership authorization as
  any other room type — a DM is "just a room" all the way through the delivery path, per
  `solution-architecture.md`'s explicit statement that there is **no** separate per-user
  `/user/queue/...` destination for DMs
- **No new ws-gateway work is required for CHAT-105** — this is confirmed already-built
  behavior, not a gap. (CHAT-114's topic-per-room-per-event-type / shared-consumer-group
  scaling gap is pre-existing and applies equally to DM and non-DM rooms; DMs don't introduce
  a new instance of that problem, they just add more rooms to an already-tracked issue —
  noted, not re-flagged as CHAT-105-specific.)

**AC-17 — Delivered/read receipts work for DM messages using the existing single-status column**
- Given A posts a message to a DM room and B's client sends the delivered/read receipt
  round-trip (already-implemented CHAT-107 flow)
- Then the message's `status` transitions `SENT` → `DELIVERED` → `READ` exactly as
  `MessageStatusService.applyDelivered`/`applyRead` already do today for any room
- This is explicitly the case `solution-architecture.md` calls out as sufficient without a
  separate `message_receipts(message_id, user_id, status)` table: "For a single recipient
  (DMs, CHAT-105) a single per-message status is sufficient." The multi-participant
  per-recipient fan-out problem that table would solve does not apply to DMs (exactly one
  possible recipient) — confirmed here as a non-gap for CHAT-105, in contrast to
  multi-member rooms where it remains an open, separately-tracked concern

---

## Leaving a DM (flag — behavior currently undefined by product decision)

**AC-18 (flag, not a defect — but needs a decision before it can be a real AC) — Leaving a DM room**
- `POST /rooms/{roomId}/leave` is **not** type-restricted in the current implementation
  (`RoomService.leaveRoom` has no `RoomType` check) — a DM participant can call it today
  against a DIRECT room and it will "succeed" by CHAT-104's existing generic rules: the
  caller's `room_members` row is deleted, the room and message history persist (same as
  CHAT-104 AC-17/AC-19), and since a DM member's `role` is always `MEMBER` (never `OWNER`),
  the ownership-transfer branch (AC-18 in the CHAT-104 doc) never fires.
- **What is undefined:** if the departed participant (or the remaining one) subsequently
  calls `POST /rooms/direct` for the same pair again, does find-or-create (a) recognize the
  existing `direct_key`, see the caller is no longer a member, and **re-insert** their
  membership row (a "rejoin"), or (b) find the existing room and return it as-is **without**
  re-adding the departed member (leaving the DM permanently one-sided), or (c) hit the
  `direct_key` `UNIQUE` constraint in a way that wasn't designed for this case? None of these
  are specified in `solution-architecture.md`, and no AC above resolves it — writing a
  testable AC here would mean inventing product behavior, not describing a decided one.
- **Recommendation:** either (a) explicitly decide "DM leave" is out of Sprint 1 scope and
  have chat-service reject `POST /rooms/{roomId}/leave` for `type = DIRECT` with a clear
  error (simplest, avoids the undefined re-add case entirely), or (b) decide the intended
  product behavior for "leaving"/"deleting" a DM conversation and let developer implement
  find-or-create's re-add semantics accordingly. This is a product/UX call at its root (should
  users be able to leave/hide a DM at all in Sprint 1?) but the *chat-service-internal*
  mechanics of option (b) — the find-or-create race/idempotency handling for a returning
  member — are a technical design call. Both halves are listed in "Needs solution-architect
  follow-up" below since they block writing a real AC either way.

---

## FR-03 coverage assessment

FR-03 states: *"Users must be able to start private conversations with another user. Private
conversations must maintain message history. Users must receive real-time updates for
incoming private messages."*

- **Start private conversations**: covered by AC-1 through AC-9, contingent on the
  find-or-create endpoint actually being built (currently rejected — see the doc header).
  **Not yet satisfied in code**; ACs describe target behavior only.
- **Message history**: covered by AC-10, AC-11, AC-13 — fully reuses CHAT-104's shipped
  history endpoint and pagination design, so once a DIRECT room exists, this half of FR-03 is
  effectively already built. **Satisfied by reuse**, no new work.
- **Real-time updates**: covered by AC-16, AC-17 — fully reuses CHAT-107's shipped delivery
  and receipt mechanism. **Satisfied by reuse**, no new ws-gateway work required.
- **No conflict found** with FR-05 (real-time delivery: DMs use the identical event/transport
  path as rooms, nothing DM-specific contradicts it) or FR-02 (DMs are additive to the room
  model, not a competing one).
- **Genuine gap**: the find-or-create endpoint itself (AC-1–AC-9) and its underlying
  `direct_key` DB support do not exist yet — this is the actual CHAT-105 build, everything
  else above is confirming reuse of already-shipped CHAT-104/CHAT-107 mechanics.

---

## Database migration status (confirmed by reading the actual schema)

`services/chat-service/src/main/resources/db/migration/` currently has only:
- `V1__create_chat_schema.sql` — creates `rooms`, `room_members`, `messages`; `rooms.type`
  CHECK already includes `'DIRECT'`, `rooms.name` is already nullable. **No `direct_key`
  column exists.**
- `V2__index_room_members_user_id.sql` — unrelated index, not DM-related.

**A new Flyway migration (`V3__...`) is required** before AC-1 can pass: add
`rooms.direct_key VARCHAR(511)` (nullable — null/unused for PUBLIC/PRIVATE, per
`solution-architecture.md`) plus a `UNIQUE` index on it. Postgres unique indexes permit
multiple `NULL`s by default, which is the correct behavior here (many PUBLIC/PRIVATE rooms,
all with `direct_key IS NULL`, must not collide with each other) — worth stating explicitly
so it isn't second-guessed as a bug during implementation.

---

## Needs solution-architect follow-up (technical design calls, not resolved here)

1. **Exact API contract for find-or-create** (AC-1–AC-9 assume `POST /rooms/direct` with
   `{ "userId": ... }`, `201`/`200` split on created-vs-found). This is a genuine API-contract
   decision (route, verb, request/response shape, status-code convention) — solution-architect
   owns API contracts per the team's division of responsibility; this document proposes a
   default consistent with the existing `/rooms` collection convention but does not consider
   it ratified.
2. **Concurrent find-or-create race handling.** Two users (or the same pair racing two
   requests) calling find-or-create for the same `direct_key` at the same instant will both
   pass a "does it exist?" check before either commits, then one loses to the `UNIQUE`
   constraint on `direct_key`. `RoomService.joinRoom` already has a proven pattern for this
   exact shape of race (`REQUIRES_NEW` self-proxy insert + catch `DataIntegrityViolationException`
   + re-read the winner's row) — recommend reusing it verbatim for CHAT-105, but that's a
   reuse-vs-new-pattern call for solution-architect/developer to confirm, not something a
   business-facing AC should silently assume.
3. **DM "leave" semantics** (AC-18) — both halves: (a) the product decision of whether
   leaving/hiding a DM is in scope at all for Sprint 1, and (b) if it is, the find-or-create
   re-add-on-return mechanics. Flagged above; repeated here since it's the one AC in this
   document that could not be written as a testable Given/When/Then without first resolving
   an open design question.
4. **Target-user existence validation** (AC-6) — not strictly a design call so much as a
   scope/precedent-consistency call: confirm "no cross-service validation, consistent with
   CHAT-104" is the intended Sprint 1 answer rather than an unexamined gap.

---

## Summary
- **17 numbered ACs** written (AC-1 through AC-17), plus **AC-18** which is deliberately left
  as an open flag rather than a resolved testable criterion (see above).
- **4 items** flagged for solution-architect follow-up (API contract shape, concurrency
  race-handling pattern, DM-leave semantics, target-user-validation scope confirmation).
- **DB migration**: `direct_key` does **not** exist in the current schema (`V1`/`V2` checked
  directly) — a new `V3` migration is required before any of AC-1–AC-9 can pass against a
  real database.
- Reuse confirmed with **zero new work** needed for: message posting/history (AC-10–AC-13),
  member listing for DM display-name resolution (AC-15), and real-time delivery/receipts
  (AC-16–AC-17) — CHAT-105's actual net-new implementation surface is narrower than the
  feature name suggests: the find-or-create endpoint, its authorization/validation rules, and
  the `direct_key` migration.
