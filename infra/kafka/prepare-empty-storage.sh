#!/usr/bin/env bash
# Explicit fresh-root preparation only. Never called by normal server startup.
set -euo pipefail
[[ $# == 1 && ( "$1" == /var/lib/kafka/data || "$1" == /target ) ]] ||
  { echo 'Storage preparation root rejected' >&2; exit 2; }
[[ "$(id -u):$(id -g)" == 1000:1000 ]] ||
  { echo 'Storage preparation requires uid/gid 1000' >&2; exit 1; }
root=$1
[[ -d "$root" && ! -L "$root" ]] ||
  { echo 'Storage preparation requires a real directory' >&2; exit 1; }
first_entry=$(find "$root" -mindepth 1 -maxdepth 1 -print -quit) ||
  { echo 'Storage preparation could not inspect the root' >&2; exit 1; }
[[ -z "$first_entry" ]] ||
  { echo 'Storage preparation requires an empty root' >&2; exit 1; }
# Docker seeds the pinned image's new volume root as appuser:root (1000:0).
# Change only this verified empty root, never descendants or existing data.
case "$(stat -c '%u:%g' "$root")" in
  1000:0) chgrp 1000 "$root" ;;
  1000:1000) ;;
  *) echo 'Unexpected fresh storage ownership; refusing preparation' >&2; exit 1 ;;
esac
[[ "$(stat -c '%u:%g' "$root")" == 1000:1000 ]] ||
  { echo 'Fresh storage group normalization failed' >&2; exit 1; }
