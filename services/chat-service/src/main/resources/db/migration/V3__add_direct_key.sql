-- CHAT-105: direct_key identifies a DIRECT room's canonical (sorted) user
-- pair (min(subA,subB) + ':' + max(subA,subB)), unique across rooms so at
-- most one DM room exists per pair. Plain UNIQUE index, not a partial index
-- (e.g. WHERE type = 'DIRECT') - the latter isn't supported by the H2
-- substitute the @DataJpaTest suites run against (same class of problem that
-- ruled out ON CONFLICT elsewhere in this codebase - see
-- docs/guides/how-security-works.md around lines 415-438). A plain unique
-- index still works because direct_key is null for PUBLIC/PRIVATE rooms and
-- both Postgres and H2 allow multiple NULLs under a unique constraint.
ALTER TABLE rooms ADD COLUMN direct_key VARCHAR(511);

CREATE UNIQUE INDEX idx_rooms_direct_key ON rooms (direct_key);
