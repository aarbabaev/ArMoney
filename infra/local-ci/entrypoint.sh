#!/usr/bin/env bash
set -euo pipefail
umask 077
cd /opt/runner

# A stopped container must never silently re-use runner credentials or dirty work.
if [[ -e .runner || -e /work/.armoney-runner-used ]]; then
  echo 'This runtime has already been used. Remove the disposable CI stack and volumes before registering again.' >&2
  exit 1
fi
token_file=/run/secrets/registration-token
[[ -s "$token_file" ]] || { echo 'Missing short-lived registration token.' >&2; exit 1; }
registration_token=$(tr -d '\r\n' < "$token_file")
[[ "$registration_token" =~ ^[A-Za-z0-9_]+$ ]] || { echo 'Invalid registration token format.' >&2; exit 1; }
docker info >/dev/null
touch /work/.armoney-runner-used
# GitHub removes an ephemeral runner after its single job. No PAT enters this container.
./config.sh --unattended --ephemeral --disableupdate \
  --url https://github.com/aarbabaev/ArMoney \
  --token "$registration_token" \
  --name "${RUNNER_NAME:-armoney-local-ephemeral}" \
  --labels armoney-local --work /work
unset registration_token
echo 'ArMoney ephemeral runner registered. Erase the host temporary token file contents now.'
# Do not accept work until the host confirms erasure through the mounted inode.
for ((attempt=0; attempt<120; attempt++)); do
  if [[ -f "$token_file" && ! -s "$token_file" ]]; then
    # Registration files retain mode 0600; job files must be readable by
    # non-root users in bind-mounted PostgreSQL and other test containers.
    umask 022
    exec ./run.sh
  fi
  sleep 1
done
echo 'Registration token was not erased within 120 seconds; refusing to accept jobs.' >&2
exit 1
