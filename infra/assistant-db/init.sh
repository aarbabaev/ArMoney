#!/usr/bin/env bash
set -euo pipefail
test -n "${ASSISTANT_RUNTIME_DB_PASSWORD:-}"
# psql literal quoting binds the generated password; never print it or build SQL from it.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --set=runtime_password="$ASSISTANT_RUNTIME_DB_PASSWORD" <<'SQL'
CREATE ROLE assistant_reader NOLOGIN;
SELECT format('CREATE ROLE assistant_runtime LOGIN PASSWORD %L IN ROLE assistant_reader', :'runtime_password') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE assistant TO assistant_runtime;
SQL
