# JIRA Sprint Board — Sprint 1

> Note: this file is a local simulated board, independent of the real Jira project at
> myepiskoposyan.atlassian.net (project CHAT, id 10033). Real-project numbering doesn't match
> these local CHAT-1xx ids. A pre-existing crosswalk from an earlier session already covers
> local CHAT-101→real CHAT-2, 102→3, 103→4, 104→5, 105→6, 106→7, 107→8, 108→9, 109→10, 110→11,
> 111→12, 112→13 (each real ticket carries an "[Internal ref: CHAT-1xx]" tag in its
> description). This session additionally created real CHAT-41 (=local 114) and CHAT-42
> (=local 115), which had no prior real counterpart. An earlier attempt in this session to
> also sync 105/106/108/109/110 duplicated the pre-existing CHAT-6/7/9/10/11 and was deleted.

## To Do
- CHAT-106 Implement file upload and download flow
- CHAT-108 Create deployment pipeline and environments
- CHAT-110 Create performance test plan and metrics
- CHAT-111 Sprint 1 planning and coordination
- CHAT-112 Track sprint status and stakeholder reporting
- CHAT-114 Fix Kafka topic-per-room and shared-consumer-group scaling gap (CHAT-24)
  - Stopgap: no-op @KafkaHandler(isDefault=true) added to ReceiptEventListener and RoomEventBroadcastListener (fixes dispatch-error symptom)
  - Real scope: topic-per-room proliferation and shared ws-gateway consumer group breaking multi-pod delivery remain open; needs narrower per-service topic patterns and per-pod consumer identity per solution-architecture.md § 'Messaging backbone scaling'
- CHAT-115 solution-architecture.md's Kafka event catalog section doesn't match real event/topic contracts
  - Event envelope and example topics documented incorrectly; need reconciliation against actual implementation

## In Progress
- CHAT-109 Define QA strategy and smoke tests
  - 20-case smoke test plan derived from CHAT-105 ACs
  - Test cases C/D (real-time + status) no longer blocked by CHAT-107 (now Done); re-validate timing
  - Tests A/B (conversation creation, message history) ready to execute; CHAT-102 API contracts available (Done)

## Done
- CHAT-101 Document business requirements and acceptance criteria
  - Artifact: docs/functional-requirements.md (comprehensive, covers FR-01 through FR-06)
- CHAT-102 Design architecture and API contracts
  - Artifact: docs/solution-architecture.md (service boundaries, data flows, core flows, technology baseline, authentication/identity, chat rooms/messaging, real-time delivery, scalability/HA design)
- CHAT-103 Implement authentication and user profiles
  - Code: services/profile-service (ProfileService, ProfileController, domain, repository, presence reader)
  - Verified: real business logic, not scaffolds
- CHAT-104 Implement chat room creation and messaging
  - Code: services/chat-service (RoomService, RoomController, MessageService, MessageController, domain, exception hierarchy, Kafka listeners)
  - Artifact: docs/acceptance-criteria-chat-104.md (39 ACs, retroactively documented after implementation; covers room creation/listing/join/leave, message posting/history, identity/authz)
  - Verified: real business logic, not scaffolds; 25 passing smoke tests per QA earlier this session
- CHAT-107 Implement real-time message delivery
  - Code: services/ws-gateway (STOMP-over-WebSocket via Kafka consumer, delivered/read receipts, CONNECT-time JWT auth, subscribe-time membership authz, Redis presence writes)
  - Verified: real business logic; RoomEventBroadcastListener, PresenceService, auth/membership checkers all implemented
- CHAT-105 Implement one-to-one direct messaging
  - Code: services/chat-service (RoomService.findOrCreateDirect, POST /rooms/direct endpoint, V3__add_direct_key.sql migration, SelfDmNotAllowedException)
  - Artifact: docs/acceptance-criteria-chat-105.md (17 ACs covering find-or-create, messaging reuse, visibility, real-time delivery; AC-18 deliberately unresolved as out-of-scope per product decision)
  - Verified: all 17 ACs validated by QA; 5 additional tests added by developer exercising real DIRECT room message posting/history/join-rejection/member-listing; mvn -pl services/chat-service -am test: 93/93 passing
- CHAT-113 Migrate messaging backbone from RabbitMQ to Kafka
  - Artifact: docs/kafka-migration-plan.md (all 5 phases completed; common-messaging, chat-service, ws-gateway migrated; docker-compose and .env updated)
  - Verified: platform/common-messaging RabbitEventPublisher removed, replaced with RabbitEventPublisher (Kafka-backed); chat-service and ws-gateway using @KafkaListener
