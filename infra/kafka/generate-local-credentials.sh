#!/usr/bin/env bash
# Linux/JDK prerequisite. Generates credentials only; never contacts Docker/Kafka.
set -euo pipefail
umask 077
[[ $# == 1 && "$1" == /* ]] || { echo 'Provide one absolute external credentials directory' >&2; exit 2; }
command -v keytool >/dev/null || { echo 'JDK keytool required' >&2; exit 1; }
[[ "$(id -u)" == 0 || "$(id -u)" == 1000 ]] || { echo 'Generate as uid 1000 or root for the pinned image user' >&2; exit 1; }
repository=$(cd "$(dirname "$0")/../.." && pwd -P)
destination=$(realpath -m -- "$1")
case "$destination/" in "$repository/"*) echo 'Credentials must be outside the repository' >&2; exit 1 ;; esac
[[ ! -e "$destination" && ! -L "$destination" ]] || { echo 'Refusing to replace existing credentials' >&2; exit 1; }
mkdir -m 700 -- "$destination"
trap 'echo "Credential generation incomplete; preserve/review the external directory before retrying" >&2' ERR
random_id() { head -c 16 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
random_password() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
quiet_keytool() { keytool "$@" >/dev/null 2>&1; }
mkdir -m 700 "$destination/ca"
random_password > "$destination/ca/password"
quiet_keytool -genkeypair -alias ca -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -dname CN=armoney-kafka-local-ca -ext BC=ca:true -ext KU=keyCertSign,cRLSign -validity 3650 -storetype PKCS12 -keystore "$destination/ca/keystore.p12" -storepass:file "$destination/ca/password" -keypass:file "$destination/ca/password" -noprompt
quiet_keytool -exportcert -rfc -alias ca -keystore "$destination/ca/keystore.p12" -storepass:file "$destination/ca/password" -file "$destination/ca/ca.crt"
cluster=$(random_id)
initial="1@kafka-controller-1:9093:$(random_id),2@kafka-controller-2:9093:$(random_id),3@kafka-controller-3:9093:$(random_id)"
printf '%s\n' "$cluster" > "$destination/cluster.id"
printf '%s\n' "$initial" > "$destination/initial-controllers"
for principal in kafka-controller-{1,2,3} kafka-broker-{1,2,3} admin payment ledger denied; do
  directory="$destination/$principal"
  mkdir -m 700 "$directory"
  random_password > "$directory/password"
  cp "$destination/ca/ca.crt" "$directory/ca.crt"
  cp "$destination/cluster.id" "$directory/cluster.id"
  cp "$destination/initial-controllers" "$directory/initial-controllers"
  quiet_keytool -genkeypair -alias node -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -dname "CN=$principal" -validity 90 -storetype PKCS12 -keystore "$directory/keystore.p12" -storepass:file "$directory/password" -keypass:file "$directory/password" -noprompt
  quiet_keytool -certreq -alias node -keystore "$directory/keystore.p12" -storepass:file "$directory/password" -file "$directory/request.csr"
  usage=clientAuth
  case "$principal" in kafka-controller-*|kafka-broker-*) usage=serverAuth,clientAuth ;; esac
  quiet_keytool -gencert -rfc -alias ca -keystore "$destination/ca/keystore.p12" -storepass:file "$destination/ca/password" -infile "$directory/request.csr" -outfile "$directory/certificate.crt" -validity 90 -ext "EKU=$usage" -ext "SAN=dns:$principal,dns:localhost,ip:127.0.0.1" -ext KU=digitalSignature,keyEncipherment
  quiet_keytool -importcert -noprompt -alias ca -keystore "$directory/keystore.p12" -storepass:file "$directory/password" -file "$directory/ca.crt"
  quiet_keytool -importcert -noprompt -alias node -keystore "$directory/keystore.p12" -storepass:file "$directory/password" -file "$directory/certificate.crt"
  quiet_keytool -importcert -noprompt -alias ca -storetype PKCS12 -keystore "$directory/truststore.p12" -storepass:file "$directory/password" -file "$directory/ca.crt"
  password=$(cat "$directory/password")
  cat > "$directory/client.properties" <<PROPERTIES
security.protocol=SSL
ssl.keystore.type=PKCS12
ssl.keystore.location=/run/kafka-secrets/keystore.p12
ssl.keystore.password=$password
ssl.key.password=$password
ssl.truststore.type=PKCS12
ssl.truststore.location=/run/kafka-secrets/truststore.p12
ssl.truststore.password=$password
ssl.endpoint.identification.algorithm=https
enable.idempotence=true
acks=all
max.in.flight.requests.per.connection=5
retries=2147483647
request.timeout.ms=5000
delivery.timeout.ms=15000
max.block.ms=15000
default.api.timeout.ms=15000
allow.auto.create.topics=false
enable.auto.commit=false
PROPERTIES
  unset password
done
# Parent and every credential remain private. Image appuser is uid/gid 1000.
if [[ "$(id -u)" == 0 ]]; then chown -R 1000:1000 -- "$destination"; fi
trap - ERR
echo 'Credentials generated outside the repository; keep the CA offline and private'

