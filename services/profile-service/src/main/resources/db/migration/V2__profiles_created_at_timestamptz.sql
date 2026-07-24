-- V1 defined created_at as TIMESTAMP (no time zone), while the entity maps
-- java.time.Instant. Hibernate 6 normalizes to UTC on write so this worked,
-- but it silently loses offset semantics for anything reading the column
-- directly. V1 is treated as already-applied/immutable (Flyway convention),
-- so this widens the column type in a new migration rather than editing V1.
ALTER TABLE profiles
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;
