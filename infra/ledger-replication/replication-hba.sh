#!/bin/sh
set -eu
rule='host replication ledger_replication all scram-sha-256'
if ! grep -qxF "$rule" "$PGDATA/pg_hba.conf"; then
  printf '\n%s\n' "$rule" >> "$PGDATA/pg_hba.conf"
fi
