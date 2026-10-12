# Disposable local Actions runtime

This Linux x64 runtime registers one ephemeral runner for `aarbabaev/ArMoney`.
GitHub supplies the default `self-hosted`, `Linux`, `X64` labels; registration adds
`armoney-local`. The controller can rotate a bounded number of single-job runners
or keep rotating in explicitly selected foreground continuous mode.
Each rotation deletes the inner Docker daemon's data and workspace. Only the
external `armoney-ci-artifacts` volume survives. An offline machine cannot run CI.
The runner process exit code is **not** the GitHub job result; inspect Actions for
actual test outcomes.

## Trust boundary

Use this only for owner-reviewed code and trusted workflows. A label or a job-level
actor condition is not a security boundary: another workflow can target the same
runner. For a public repository, require approval for **all external contributors**
before any fork workflow runs, and never approve unreviewed fork code for this
runner. Repository collaborators with workflow write access are trusted. Do not
use `pull_request_target` to execute PR code. The controller checks approval policy
and audits all active workflow states before each registration, rejecting external
or missing actor/repository identities and unexpected event types. This is a
point-in-time check, not a job assignment lock or workflow allowlist. Repository Actions
settings and review of every workflow remain prerequisites to registration.

The runner has no host Docker socket, repository, home directory, SSH keys or PAT
mounted. It controls a separate privileged Docker-in-Docker daemon through a Unix
socket volume. Both containers mount `/work` at the same absolute path, making
Testcontainers and Compose bind mounts refer to the checkout in that volume.
The runner shares the daemon's network namespace so inner published ports are
reachable through `localhost`, including the existing smoke tests' port 8080.
There are no outer published ports or TCP Docker listeners. The inner daemon
cannot accidentally operate the user's existing Docker objects through its API.

Privileged DinD is **not** a hostile-code sandbox: kernel/container escapes can
compromise the Docker Desktop VM and potentially the host. Outbound networking is
available and is not an exfiltration firewall. Run on a dedicated machine/VM for
stronger separation. Neither privileged DinD nor the artifact volume is a safe
boundary between mutually untrusted jobs. Local reports may contain synthetic
test data; preserve only intended reports/APKs and apply retention periodically.

## Build and run (PowerShell, repository root)

Read and accept the [Android SDK terms](https://developer.android.com/studio#command-line-tools-only)
before setting the build argument. License text is suppressed in build logs.

```powershell
docker build --build-arg ACCEPT_ANDROID_LICENSES=true -t armoney-local-runner:2.338.0 infra/local-ci
.\infra\local-ci\Start-LocalRunner.ps1
# Or rotate two isolated runners, allowing the queued build and Android jobs to run serially:
.\infra\local-ci\Start-LocalRunner.ps1 -Jobs 2
# Keep rotating in the foreground until Ctrl+C or a preflight/runtime error:
.\infra\local-ci\Start-LocalRunner.ps1 -Continuous
```

The script reads `GITHUB_PAT_TOKEN` from the host process or user environment. It
must have repository Actions runner administration permission. Never paste that
token into Compose, a command argument, the repository or a container. The script
requests a short-lived registration token through GitHub's API, writes it into an
ACL-restricted temporary directory outside the repository, and supplies only that
file as a read-only Compose secret. It truncates the file after registration and
removes it on exit. The runner waits up to 120 seconds for the mounted token file
to become empty before polling for jobs; missing files or timeout fail closed.
The PAT remains solely in the host process/environment. Runner authentication
credentials exist only in the disposable runner container's writable layer.

Do not run parallel controllers or scale the runner service. A host mutex and an
existing-container and leftover-volume checks reject competing or dirty sessions.
Cleanup failures stop rotation, including when containers are gone but daemon data
volumes remain. The external artifact volume is the only preserved volume.
The image must already be
built: the controller uses `--no-build`. It ignores repository `.env` files by
passing an empty environment file. Stop with Ctrl+C; its `finally` block removes
this CI stack and any remaining registration matching the generated runner name.
Successful ephemeral jobs deregister automatically. Restarting a stopped runner
or reusing its workspace fails closed. A new job requires a new registration,
container and workspace. Continuous mode remains a foreground process and stops
on any preflight/runtime error. Each rotation repeats the approval-policy and
active-run audit before requesting a token. The controller does not automatically
restart on login, host reboot or failure.

## Recovery and removal

Reports and APKs saved under `/artifacts` are local Docker-volume files, not
GitHub Actions artifacts. Export them to a new directory of your choice outside
the repository. This stopped helper mounts the volume read-only; it never starts
the runner or executes a job:

```powershell
$ciExportDirectory = Join-Path $env:USERPROFILE 'Downloads\ArMoney-CI-reports'
New-Item -ItemType Directory -Path $ciExportDirectory -ErrorAction Stop | Out-Null
$ciExportContainer = docker create --mount type=volume,src=armoney-ci-artifacts,dst=/artifacts,readonly --entrypoint /bin/true armoney-local-runner:2.338.0
if ($LASTEXITCODE -ne 0) { throw 'Could not create the artifact export helper.' }
try {
    docker cp "${ciExportContainer}:/artifacts/." $ciExportDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Artifact export failed.' }
} finally {
    docker rm $ciExportContainer
}
```

Extract `backend-tests.tgz` or `android-results.tgz` from the exported run directory,
then open the HTML reports in a browser or install the debug APK.
The artifact volume remains intact. After a host reboot, start Docker Desktop,
complete the cleanup below if resources remain, then manually run
`.\infra\local-ci\Start-LocalRunner.ps1 -Continuous` again.

After a forced process kill or host reboot, inspect the exact project before
removing it. Never substitute the application's persistent Compose project here.
The following command removes **only** `armoney-ci-buildmachine` resources and its
disposable volumes; the external artifact volume remains.

```powershell
docker ps -a --filter label=com.docker.compose.project=armoney-ci-buildmachine
$ciEmptyFile = [IO.Path]::GetTempFileName()
$ciPreviousTokenFile = $env:RUNNER_TOKEN_FILE
try {
    $env:RUNNER_TOKEN_FILE = $ciEmptyFile
    docker compose --project-name armoney-ci-buildmachine --env-file $ciEmptyFile -f infra/local-ci/compose.yaml down --volumes --remove-orphans
} finally {
    $env:RUNNER_TOKEN_FILE = $ciPreviousTokenFile
    Remove-Item -LiteralPath $ciEmptyFile
}
```

Remove an interrupted runner's exact `armoney-local-<guid>` entry in repository
Settings → Actions → Runners. Inspect the matching host temporary directory and
delete its `registration-token` and `empty.env` files if an ungraceful termination
left them behind. Do not delete unrelated temporary directories. To fully uninstall
after exporting desired artifacts, explicitly remove `armoney-ci-artifacts` with
`docker volume rm armoney-ci-artifacts`, then remove image
`armoney-local-runner:2.338.0`. Do not use global Docker prune commands.

## Toolchain and limits

The image contains Temurin Java 21.0.8+9, Docker CLI/Compose/Buildx from the Docker
29.8.1 CLI image, Python 3 from Ubuntu Noble with Playwright 1.55.0 and Chromium,
and Actions runner 2.338.0 (including its bundled Node runtimes). The runner archive
is SHA-256 checked against the [official release](https://github.com/actions/runner/releases/tag/v2.338.0).
Android command-line tools build 15859902 are SHA-256 checked against Google's
download page. Platform-tools 37.0.1 and emulator 37.2.12 use fixed archive URLs
and the checksums from [Google's package manifest](https://dl.google.com/android/repository/repository2-1.xml).
SDK platform 36 and build-tools 35.0.0 are installed through `sdkmanager`.

The backend CI job sets `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` for Docker Java client
compatibility. Testcontainers 1.21.3's docker-java default API 1.32 is rejected by
Docker 29.8.1, which requires at least API 1.40. This backend-only pin selects a
supported API while preserving the current daemon and real PostgreSQL integration
tests. It is not set globally on the runner: Java's environment notice interferes
with Android SDK launcher version parsing. Reassess the pin when updating
Testcontainers/docker-java or Docker.

These are version pins, not a fully reproducible image: base image tags, apt
security updates, transitive Python dependencies and SDK platform revisions can
change. SDK system images are downloaded by the job, not retained in the base
image. Record the built image digest and installed versions with validation
evidence. Review and update toolchain pins regularly. Runner auto-update is
disabled; rebuild before GitHub stops accepting an outdated runner (normally
within 30 days of a new release, sooner for critical updates).

Routine Android CI runs JVM tests, lint and APK assembly without an emulator.
Emulator instrumentation runs only after an explicit owner request; then use
`bash scripts/local-ci-android.sh --owner-requested` in the disposable runtime.
The flag records intent; it is not an authorization boundary. Tests remain in source.
Docker Desktop on this host has no `/dev/kvm`; requested emulator runs use software
emulation (`-accel off`) with a longer bounded timeout. Routine CI provides no
real-device/Keystore instrumentation evidence.
iOS development and CI are paused by the owner. No macOS runner is required for
the currently active CI scope; iOS simulator tests cannot run in these Linux containers.
Allow substantial disk space for the image, inner image builds, Android system
image/userdata, and reports. Fresh daemon state favors isolation over build speed;
multiple jobs re-download dependencies. No Docker build or runtime validation is
implied by the presence of these files.
