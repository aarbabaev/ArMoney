#!/bin/sh
set -eu
# The official entrypoint creates pg_hba.conf on a fresh volume. Append the
# restricted replication rule after that initialization, before normal startup.
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  mkdir -p /docker-entrypoint-initdb.d
  cp /opt/ledger-replication/replication-hba.sh /docker-entrypoint-initdb.d/99-ledger-replication.sh
else
  /bin/sh /opt/ledger-replication/replication-hba.sh
fi
exec /usr/local/bin/docker-entrypoint.sh "$@"
