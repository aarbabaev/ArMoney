#!/usr/bin/env bash
# Explicit operator action only. Server startup never calls this script.
set -euo pipefail
bash /opt/armoney/prepare-config.sh
cluster=$(cat /run/kafka-secrets/cluster.id)
[[ "$cluster" =~ ^[A-Za-z0-9_-]{22}$ ]] || { echo 'Invalid cluster identity' >&2; exit 1; }
meta=/var/lib/kafka/data/meta.properties
if [[ -e "$meta" ]]; then
  grep -Fxq "cluster.id=$cluster" "$meta" && grep -Fxq "node.id=$KAFKA_NODE_ID" "$meta" ||
    { echo 'Existing storage identity mismatch; refusing format' >&2; exit 1; }
  echo 'Storage already initialized with matching identity'
  exit 0
fi
if [[ -n "$(find /var/lib/kafka/data -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo 'Nonempty unrecognized storage; refusing format' >&2
  exit 1
fi
bash /opt/armoney/prepare-empty-storage.sh /var/lib/kafka/data
if [[ "$KAFKA_NODE_ROLE" == controller ]]; then
  initial=$(cat /run/kafka-secrets/initial-controllers)
  [[ "$initial" =~ ^1@kafka-controller-1:9093:[A-Za-z0-9_-]{22},2@kafka-controller-2:9093:[A-Za-z0-9_-]{22},3@kafka-controller-3:9093:[A-Za-z0-9_-]{22}$ ]] ||
    { echo 'Invalid initial controller manifest' >&2; exit 1; }
  /opt/kafka/bin/kafka-storage.sh format --cluster-id "$cluster" --config /tmp/server.properties --initial-controllers "$initial"
else
  /opt/kafka/bin/kafka-storage.sh format --cluster-id "$cluster" --config /tmp/server.properties --no-initial-controllers
fi

