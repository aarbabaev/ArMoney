#!/usr/bin/env bash
set -euo pipefail
bootstrap=kafka-broker-1:9092,kafka-broker-2:9092,kafka-broker-3:9092
config=/run/kafka-secrets/client.properties
case "${1:-}" in
  produce)
    [[ $# == 2 ]] || exit 2
    exec /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$bootstrap" --command-config "$config" --sync --topic "$2" --reader-property parse.key=true --reader-property key.separator=:
    ;;
  consume)
    [[ $# == 5 && "$4" =~ ^[1-9][0-9]{0,5}$ && "$5" =~ ^[1-9][0-9]{0,6}$ ]] || exit 2
    exec /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$bootstrap" --command-config "$config" --topic "$2" --group "$3" --from-beginning --max-messages "$4" --timeout-ms "$5" --formatter-property print.key=true --formatter-property key.separator=:
    ;;
  topics)
    [[ $# == 1 ]] || exit 2
    exec /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --command-config "$config" --describe
    ;;
  quorum)
    [[ $# == 1 ]] || exit 2
    exec /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server "$bootstrap" --command-config "$config" describe --status
    ;;
  *) echo 'Usage: client.sh produce TOPIC | consume TOPIC GROUP MAX_MESSAGES TIMEOUT_MS | topics | quorum' >&2; exit 2 ;;
esac

