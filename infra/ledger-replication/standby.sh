#!/bin/sh
set -eu
: "${PGDATA:?}" "${REPLICA_NAME:?}" "${LEDGER_REPLICATION_PASSWORD:?}"
case "$LEDGER_REPLICATION_PASSWORD" in
  *'
'*|*"$(printf '\r')"*) echo 'Replication password must not contain line breaks' >&2; exit 1 ;;
esac
case "$REPLICA_NAME" in ledger_replica1|ledger_replica2) ;; *) exit 1 ;; esac
mkdir -p "$PGDATA" /var/lib/postgresql
chown postgres:postgres "$PGDATA" /var/lib/postgresql
chmod 700 "$PGDATA"
if [ "$(id -u)" = 0 ]; then
  # PostgreSQL's official Alpine image supplies gosu (the same helper used by
  # docker-entrypoint.sh), rather than Alpine's optional su-exec package.
  exec gosu postgres /bin/sh "$0" "$@"
fi
# pgpass requires escaping colon and backslash; never put the password in argv,
# primary_conninfo, a generated Compose manifest, or diagnostic output.
umask 077
escaped=$(printf '%s' "$LEDGER_REPLICATION_PASSWORD" | sed 's/\\/\\\\/g; s/:/\\:/g')
printf 'ledger-db:5432:replication:ledger_replication:%s\n' "$escaped" > /var/lib/postgresql/.pgpass
chmod 600 /var/lib/postgresql/.pgpass
unset escaped LEDGER_REPLICATION_PASSWORD
export PGPASSFILE=/var/lib/postgresql/.pgpass
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  if [ -n "$(find "$PGDATA" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo 'Refusing to initialize a nonempty standby data directory' >&2
    exit 1
  fi
  pg_basebackup -d "host=ledger-db port=5432 user=ledger_replication application_name=$REPLICA_NAME passfile=/var/lib/postgresql/.pgpass" -D "$PGDATA" -R -X stream -S "$REPLICA_NAME" --checkpoint=fast --no-password
fi
if [ ! -f "$PGDATA/standby.signal" ]; then
  echo 'Refusing to run an existing data directory without standby.signal' >&2
  exit 1
fi
exec /usr/local/bin/docker-entrypoint.sh "$@"
