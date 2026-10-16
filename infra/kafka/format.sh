#!/usr/bin/env bash
# Explicit operator action only. Server startup never calls this script.
set -euo pipefail
# Fixed stage labels only: never print shell commands, paths or configuration.
format_phase() { printf 'ARMONEY_KAFKA_FORMAT_STAGE=%s\n' "$1" >&2; }
format_phase CONFIG
bash /opt/armoney/prepare-config.sh
format_phase CLUSTER_ID
cluster=$(cat /run/kafka-secrets/cluster.id)
[[ "$cluster" =~ ^[A-Za-z0-9_-]{22}$ ]] || { echo 'Invalid cluster identity' >&2; exit 1; }
meta=/var/lib/kafka/data/meta.properties
format_phase METADATA
if [[ -e "$meta" ]]; then
  grep -Fxq "cluster.id=$cluster" "$meta" && grep -Fxq "node.id=$KAFKA_NODE_ID" "$meta" ||
    { echo 'Existing storage identity mismatch; refusing format' >&2; exit 1; }
  echo 'Storage already initialized with matching identity'
  exit 0
fi
format_phase EMPTY_ROOT
if [[ -n "$(find /var/lib/kafka/data -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo 'Nonempty unrecognized storage; refusing format' >&2
  exit 1
fi
format_phase OWNERSHIP
bash /opt/armoney/prepare-empty-storage.sh /var/lib/kafka/data
if [[ "$KAFKA_NODE_ROLE" == controller ]]; then
  format_phase INITIAL_CONTROLLERS
  initial=$(cat /run/kafka-secrets/initial-controllers)
  [[ "$initial" =~ ^1@kafka-controller-1:9093:[A-Za-z0-9_-]{22},2@kafka-controller-2:9093:[A-Za-z0-9_-]{22},3@kafka-controller-3:9093:[A-Za-z0-9_-]{22}$ ]] ||
    { echo 'Invalid initial controller manifest' >&2; exit 1; }
  format_phase STORAGE_TOOL
  /opt/kafka/bin/kafka-storage.sh format --cluster-id "$cluster" --config /tmp/server.properties --initial-controllers "$initial"
else
  format_phase STORAGE_TOOL
  /opt/kafka/bin/kafka-storage.sh format --cluster-id "$cluster" --config /tmp/server.properties --no-initial-controllers
fi

