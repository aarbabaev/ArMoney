#!/usr/bin/env bash
set -euo pipefail
bootstrap=kafka-broker-1:9092,kafka-broker-2:9092,kafka-broker-3:9092
config=/run/kafka-secrets/client.properties
topics=(armoney.ledger.transfer.commands.v1 armoney.ledger.transfer.results.v1 armoney.payment.outcomes.v1)
for topic in "${topics[@]}"; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --command-config "$config" --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 3 --config min.insync.replicas=2 --config retention.ms=604800000 --config cleanup.policy=delete --config unclean.leader.election.enable=false --config max.message.bytes=32768
  description=$(/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --command-config "$config" --describe --topic "$topic")
  [[ "$description" =~ PartitionCount:[[:space:]]+3[[:space:]] && "$description" =~ ReplicationFactor:[[:space:]]+3[[:space:]] ]] ||
    { echo 'Existing topic partition/replication configuration differs; refusing initialization' >&2; exit 1; }
  for expected in min.insync.replicas=2 retention.ms=604800000 cleanup.policy=delete unclean.leader.election.enable=false max.message.bytes=32768; do
    # Split the reported configuration list into exact tokens, not prefix matches.
    printf '%s\n' "$description" | tr ',\t ' '\n' | grep -Fxq "$expected" ||
      { echo 'Existing topic settings differ; explicit operator migration required' >&2; exit 1; }
  done
done
acl() { /opt/kafka/bin/kafka-acls.sh --bootstrap-server "$bootstrap" --command-config "$config" --add "$@"; }
# Exact resource ACLs; producer convenience flags are avoided because they grant Create.
acl --allow-principal User:payment --operation Write --operation Describe --topic armoney.ledger.transfer.commands.v1
acl --allow-principal User:payment --operation Write --operation Describe --topic armoney.payment.outcomes.v1
acl --allow-principal User:ledger --operation Write --operation Describe --topic armoney.ledger.transfer.results.v1
acl --allow-principal User:ledger --operation Read --operation Describe --topic armoney.ledger.transfer.commands.v1
acl --allow-principal User:payment --operation Read --operation Describe --topic armoney.ledger.transfer.results.v1
acl --allow-principal User:ledger --operation Read --group ledger-transfer-v1
acl --allow-principal User:payment --operation Read --group payment-ledger-result-v1
# Nontransactional idempotent producers require cluster IdempotentWrite.
acl --allow-principal User:payment --allow-principal User:ledger --operation IdempotentWrite --cluster
echo 'Topic configuration verified and exact service ACLs installed'

