#!/bin/sh
set -eu
: "${LEDGER_REPLICATION_PASSWORD:?Dedicated replication password required}"
case "$LEDGER_REPLICATION_PASSWORD" in
  *'
'*|*"$(printf '\r')"*) echo 'Replication password must not contain line breaks' >&2; exit 1 ;;
esac
# Only this administrative connection uses local commit: standbys cannot clone
# until their role/slots exist. Application connections retain synchronous commit.
export PGOPTIONS='-c synchronous_commit=local -c statement_timeout=30000'
exec psql -X -v ON_ERROR_STOP=1 -f /opt/ledger-replication/setup.sql
