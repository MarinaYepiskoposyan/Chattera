#!/usr/bin/env bash
#
# infra/postgres/init/01-init-keycloak-db.sh
#
# Runs once, the first time the postgres container initializes an empty
# data volume, via the official postgres image's docker-entrypoint-initdb.d
# mechanism (scripts here are dot-sourced by docker-entrypoint.sh, so this
# file does not need the executable bit set).
#
# Purpose: Keycloak gets its OWN database and its OWN role, never the
# `chattera` application's role or a shared schema. The keycloak role owns
# the `keycloak` database (it needs DDL rights there for Keycloak's own
# schema migrations) but has no grants whatsoever on the `chattera`
# database.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER "${KEYCLOAK_DB_USER}" WITH PASSWORD '${KEYCLOAK_DB_PASSWORD}';
    CREATE DATABASE "${KEYCLOAK_DB_NAME}" OWNER "${KEYCLOAK_DB_USER}";
    REVOKE ALL PRIVILEGES ON DATABASE "${KEYCLOAK_DB_NAME}" FROM PUBLIC;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${KEYCLOAK_DB_NAME}" <<-EOSQL
    GRANT ALL PRIVILEGES ON SCHEMA public TO "${KEYCLOAK_DB_USER}";
EOSQL

echo "infra/postgres/init: created database '${KEYCLOAK_DB_NAME}' and role '${KEYCLOAK_DB_USER}' for Keycloak (isolated from '${POSTGRES_DB}')."
