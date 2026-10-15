#!/usr/bin/env bash
# Cold, directory-based snapshots only. The caller verifies all six nodes stopped.
set -Eeuo pipefail
snapshot_stage=SETUP
trap 'printf "SNAPSHOT_FAILURE_%s\n" "$snapshot_stage" >&2' ERR
umask 077
[[ "$(id -u):$(id -g)" == 1000:1000 ]] || { echo 'Snapshot tools require uid/gid 1000' >&2; exit 1; }
mode=${1:-}; node=${2:-}; cluster=${3:-}
case "$node" in
  kafka-controller-1) node_id=1 ;; kafka-controller-2) node_id=2 ;; kafka-controller-3) node_id=3 ;;
  kafka-broker-1) node_id=101 ;; kafka-broker-2) node_id=102 ;; kafka-broker-3) node_id=103 ;;
  *) echo 'Invalid snapshot node' >&2; exit 2 ;;
esac
[[ "$cluster" =~ ^[A-Za-z0-9_-]{22}$ ]] || { echo 'Invalid snapshot cluster' >&2; exit 2; }
case "$mode:$#" in archive:3|restore:4) ;; *) echo 'Usage: snapshot.sh archive NODE CLUSTER_ID | restore NODE CLUSTER_ID MANIFEST_SHA256' >&2; exit 2 ;; esac
work=$(mktemp -d /tmp/kafka-snapshot.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
fail() { printf 'SNAPSHOT_FAILURE_%s\n' "$snapshot_stage" >&2; echo "$1" >&2; exit 1; }
identity() {
  [[ -f "$1/meta.properties" && ! -L "$1/meta.properties" ]] || fail 'Snapshot metadata missing'
  # Kafka emits canonical keys. Reject escaped/continued properties and duplicate
  # keys, including alternate Java-properties separators, rather than accepting
  # one matching line while Java subsequently reads a conflicting value.
  ! grep -Fq '\' "$1/meta.properties" || fail 'Escaped snapshot metadata is forbidden'
  [[ "$(grep -Ec '^[[:space:]]*cluster[.]id([[:space:]]|=|:|$)' "$1/meta.properties")" == 1 &&
     "$(grep -Ec '^[[:space:]]*node[.]id([[:space:]]|=|:|$)' "$1/meta.properties")" == 1 ]] || fail 'Snapshot identity must occur exactly once'
  grep -Fxq "cluster.id=$cluster" "$1/meta.properties" && grep -Fxq "node.id=$node_id" "$1/meta.properties" || fail 'Snapshot identity mismatch'
}
bundle_metadata() {
  [[ -f "$1" && ! -L "$1" ]] || fail 'Snapshot manifest missing or unsafe'
  [[ "$(stat -c '%h' "$1")" == 1 ]] || fail 'Snapshot metadata hard links are forbidden'
  [[ "$(stat -c '%u:%g' "$1")" == 1000:1000 ]] || fail 'Snapshot metadata ownership mismatch'
}
# Reject links, devices, unexpected ownership and ambiguous paths before copying.
# Restrict names to Kafka storage names so checksum records cannot be injected.
inventory() {
  local tree=$1 list=$2 path relative
  [[ -d "$tree" && ! -L "$tree" ]] || fail 'Snapshot tree must be a real directory'
  [[ "$(stat -c '%u:%g' "$tree")" == 1000:1000 ]] || fail 'Snapshot ownership mismatch'
  find "$tree" -mindepth 1 -print0 > "$work/paths"
  : > "$list"
  while IFS= read -r -d '' path; do
    relative=${path#"$tree/"}
    [[ "$relative" =~ ^[A-Za-z0-9._/-]+$ && "/$relative/" != *'/../'* && "/$relative/" != *'/./'* ]] || fail 'Unsafe snapshot path'
    [[ ! -L "$path" && ( -f "$path" || -d "$path" ) ]] || fail 'Snapshot links and special files are forbidden'
    [[ "$(stat -c '%u:%g' "$path")" == 1000:1000 ]] || fail 'Snapshot ownership mismatch'
    if [[ -f "$path" ]]; then
      [[ "$(stat -c '%h' "$path")" == 1 ]] || fail 'Snapshot hard links are forbidden'
      printf '%s\n' "$relative" >> "$list"
    else
      printf '%s/\n' "$relative" >> "$list"
    fi
  done < "$work/paths"
  LC_ALL=C sort "$list" > "$work/sorted"
  mv "$work/sorted" "$list"
}
checksums() {
  local tree=$1 list=$2 output=$3 relative digest
  : > "$output"
  while IFS= read -r relative; do
    if [[ "$relative" == */ ]]; then
      printf 'DIRECTORY  %s\n' "$relative" >> "$output"
      continue
    fi
    digest=$(sha256sum "$tree/$relative"); digest=${digest%% *}
    printf '%s  %s\n' "$digest" "$relative" >> "$output"
  done < "$list"
}
bundle=/archive/$node
[[ -d /archive && ! -L /archive ]] || fail 'External archive mount required'
if [[ "$mode" == archive ]]; then
  [[ ! -e "$bundle" && ! -L "$bundle" ]] || fail 'Refusing to overwrite a snapshot'
  snapshot_stage=SOURCE_INVENTORY
  inventory /source "$work/source-files"
  snapshot_stage=SOURCE_IDENTITY
  identity /source
  snapshot_stage=COPY
  mkdir "$bundle"
  mkdir "$bundle/data"
  cp -pR /source/. "$bundle/data/"
  snapshot_stage=ARCHIVE_INVENTORY
  inventory "$bundle/data" "$work/archive-files"
  cmp "$work/source-files" "$work/archive-files" || fail 'Snapshot inventory changed'
  snapshot_stage=CHECKSUMS
  checksums /source "$work/source-files" "$work/source-checksums"
  checksums "$bundle/data" "$work/archive-files" "$bundle/checksums"
  cmp "$work/source-checksums" "$bundle/checksums" || fail 'Snapshot contents changed'
  snapshot_stage=MANIFEST
  identity "$bundle/data"
  digest=$(sha256sum "$bundle/checksums"); digest=${digest%% *}
  count=$(wc -l < "$work/archive-files"); count=${count//[[:space:]]/}
  printf 'version=1\nnode=%s\nnode.id=%s\ncluster.id=%s\nentries=%s\nchecksums.sha256=%s\n' "$node" "$node_id" "$cluster" "$count" "$digest" > "$bundle/manifest"
  digest=$(sha256sum "$bundle/manifest"); printf '%s\n' "${digest%% *}"
else
  snapshot_stage=MANIFEST
  expected=$4
  [[ "$expected" =~ ^[a-f0-9]{64}$ ]] || fail 'Trusted snapshot manifest checksum required'
  [[ -d "$bundle" && ! -L "$bundle" && -f "$bundle/manifest" && ! -L "$bundle/manifest" && -f "$bundle/checksums" && ! -L "$bundle/checksums" ]] || fail 'Snapshot manifest missing or unsafe'
  bundle_metadata "$bundle/manifest"
  bundle_metadata "$bundle/checksums"
  digest=$(sha256sum "$bundle/manifest"); [[ "${digest%% *}" == "$expected" ]] || fail 'Snapshot manifest checksum mismatch'
  snapshot_stage=ARCHIVE_INVENTORY
  inventory "$bundle/data" "$work/archive-files"
  identity "$bundle/data"
  snapshot_stage=CHECKSUMS
  checksums "$bundle/data" "$work/archive-files" "$work/checksums"
  cmp "$work/checksums" "$bundle/checksums" || fail 'Snapshot checksums or inventory mismatch'
  digest=$(sha256sum "$work/checksums"); digest=${digest%% *}
  count=$(wc -l < "$work/archive-files"); count=${count//[[:space:]]/}
  printf 'version=1\nnode=%s\nnode.id=%s\ncluster.id=%s\nentries=%s\nchecksums.sha256=%s\n' "$node" "$node_id" "$cluster" "$count" "$digest" > "$work/manifest"
  cmp "$work/manifest" "$bundle/manifest" || fail 'Snapshot manifest identity mismatch'
  snapshot_stage=TARGET
  bash /opt/armoney/prepare-empty-storage.sh /target
  [[ -d /target && ! -L /target && "$(stat -c '%u:%g' /target)" == 1000:1000 ]] || fail 'Restore target must belong to uid/gid 1000'
  [[ -z "$(find /target -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail 'Restore target must be empty'
  snapshot_stage=COPY
  cp -pR "$bundle/data/." /target/
  snapshot_stage=RESTORED_VERIFY
  inventory /target "$work/restored-files"
  checksums /target "$work/restored-files" "$work/restored-checksums"
  cmp "$work/restored-checksums" "$bundle/checksums" || fail 'Restored data checksum mismatch'
  identity /target
  echo 'RESTORE_VERIFIED'
fi
