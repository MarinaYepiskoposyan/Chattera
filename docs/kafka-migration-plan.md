# Kafka migration plan

## Goal
Switch the Chattera messaging backbone from RabbitMQ to Kafka while preserving the existing `EventPublisher` abstraction and keeping PostgreSQL as the source of truth.

## Scope
1. Shared messaging layer
2. Chat-service producer update
3. ws-gateway consumer update
4. Docker/local infrastructure update
5. Validation and smoke tests

## Phase 1 — shared messaging layer
- Replace RabbitMQ dependency in `platform/common-messaging`
- Add Kafka-backed `EventPublisher` implementation
- Update messaging properties to use a topic prefix model (`chattera.events` by default)
- Preserve the abstraction so callers still publish events with `publish(topic, event)`
- Add regression tests for publish success and failure handling

## Phase 2 — producer migration
- Update `chat-service` to publish domain events to Kafka topics instead of RabbitMQ
- Set a stable partition key (`roomId` or `userId`) for order-sensitive events
- Keep DB write-first semantics: persist in Postgres before publish
- Handle publish failure as best-effort and non-fatal

## Phase 3 — consumer migration
- Update `ws-gateway` to consume Kafka topics instead of listening to a RabbitMQ queue
- Validate room membership before pushing to sockets
- Keep WebSocket delivery logic and Redis presence writes unchanged

## Phase 4 — infrastructure
- Replace RabbitMQ in `docker-compose.yml`
- Add Kafka service and required environment variables
- Update dev docs and env examples to match the Kafka stack

## Phase 5 — verification
- Run the affected Maven tests
- Start the local stack
- Smoke-test a room message publish/consume flow
- Confirm failure-tolerant publish behavior and DB-first semantics

## Current status
- Architecture docs are migrated to Kafka (`docs/solution-architecture.md`), including the
  CHAT-107 delivery-decisions and CHAT-24 scalability/HA sections, which previously still
  described the old RabbitMQ mechanics.
- `platform/common-messaging`, `chat-service`, and `ws-gateway` are fully on
  `KafkaEventPublisher` / `@KafkaListener`; `docker-compose.yml` and `.env.example` run Kafka,
  not RabbitMQ. Dead legacy RabbitMQ stub classes have been removed.
- **Known gap, tracked separately as CHAT-114 (not part of this migration ticket):** the
  current Kafka wiring publishes one auto-created topic per room per event type (no partition
  key), and every ws-gateway pod shares one Kafka consumer group id. Both diverge from the
  target design in `docs/solution-architecture.md` §"Messaging backbone scaling" (a bounded
  per-event-type topic set partitioned by `roomId`/`userId`, with a fan-out-safe per-pod
  consumer identity) and need to be fixed before CHAT-24's multi-pod ws-gateway is viable.
  Harmless at Sprint 1's single-pod scale.
