# ADR 0016: Disposable local CI runners

Status: Accepted for implementation; execution evidence belongs to the delivery PR.

## Decision

GitHub Actions remains the scheduler and check-status store. Backend and Android jobs
use the `armoney-local` Linux Docker runner. Each registration handles one job; the
controller recreates its workspace and dedicated Docker daemon before the next job.
The host Docker socket, application volumes and deployment credentials are not mounted.
Reports and Android APKs persist in the separate `armoney-ci-artifacts` volume.

The Windows controller retains the repository administration token on the host and
provides only a short-lived registration token. The runner waits for its removal
before accepting work. Registration requires GitHub's `all_external_contributors`
approval policy. Checkout does not persist credentials. A failed isolation guard
prevents Docker diagnostics and cleanup as well as test execution.

## Consequences

Privileged Docker-in-Docker is not a virtual-machine security boundary. Only trusted
repository workflows may run here; approving an external workflow permits code execution
on the local machine. Workflow actor conditions and labels alone are not access controls.
Do not approve untrusted runs while this runner is available.

Android instrumentation remains required and uses software emulation where KVM is absent.
It may be substantially slower than hardware acceleration. The owner explicitly paused
iOS development and CI on 2026-10-12. iOS source and `scripts/test-ios.sh` remain, but
the workflow has no iOS job. Resumption requires a separate Mac with Xcode; Linux
Docker cannot execute its build or simulator tests. No iOS validation is claimed.

Fresh workspaces and Docker storage trade download/build speed for job isolation.
Local availability, disk capacity, runner updates and artifact retention are operator
responsibilities. No GitHub-hosted build fallback or automatic application deployment
is configured. See [operations](../../infra/local-ci/README.md).
