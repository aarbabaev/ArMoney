#!/usr/bin/env bash
set -euo pipefail
bash /opt/armoney/prepare-config.sh
meta=/var/lib/kafka/data/meta.properties
[[ -s "$meta" ]] || { echo 'Kafka storage is not initialized; explicit format or recovery required' >&2; exit 1; }
cluster=$(cat /run/kafka-secrets/cluster.id)
grep -Fxq "cluster.id=$cluster" "$meta" && grep -Fxq "node.id=$KAFKA_NODE_ID" "$meta" ||
  { echo 'Kafka storage identity mismatch; refusing startup' >&2; exit 1; }
exec /opt/kafka/bin/kafka-server-start.sh /tmp/server.properties

