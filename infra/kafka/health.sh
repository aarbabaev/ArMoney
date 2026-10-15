#!/usr/bin/env bash
set -euo pipefail
if [[ "$KAFKA_NODE_ROLE" == controller ]]; then
  exec /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-controller localhost:9093 --command-config /run/kafka-secrets/client.properties describe --status
fi
exec /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 --command-config /run/kafka-secrets/client.properties

