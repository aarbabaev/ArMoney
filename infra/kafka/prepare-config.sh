#!/usr/bin/env bash
set -euo pipefail
umask 077
case "${KAFKA_NODE_NAME:-}:${KAFKA_NODE_ID:-}:${KAFKA_NODE_ROLE:-}" in
  kafka-controller-1:1:controller|kafka-controller-2:2:controller|kafka-controller-3:3:controller|kafka-broker-1:101:broker|kafka-broker-2:102:broker|kafka-broker-3:103:broker) ;;
  *) echo 'Invalid Kafka node identity' >&2; exit 1 ;;
esac
for required in keystore.p12 truststore.p12 password cluster.id initial-controllers client.properties; do
  [[ -s "/run/kafka-secrets/$required" ]] || { echo 'Required node configuration missing' >&2; exit 1; }
done
password=$(cat /run/kafka-secrets/password)
[[ "$password" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo 'Invalid keystore password encoding' >&2; exit 1; }
cp /opt/armoney/server.properties.template /tmp/server.properties
{
  printf 'node.id=%s\nprocess.roles=%s\n' "$KAFKA_NODE_ID" "$KAFKA_NODE_ROLE"
  if [[ "$KAFKA_NODE_ROLE" == controller ]]; then
    printf 'listeners=CONTROLLER://0.0.0.0:9093\n'
  else
    printf 'listeners=BROKER://0.0.0.0:9092\nadvertised.listeners=BROKER://%s:9092\n' "$KAFKA_NODE_NAME"
  fi
  printf 'ssl.keystore.password=%s\nssl.key.password=%s\nssl.truststore.password=%s\n' "$password" "$password" "$password"
} >> /tmp/server.properties
unset password

