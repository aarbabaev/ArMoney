# ADR 0021: Two disposable local CI slots

Status: Accepted for implementation; live execution evidence belongs to the delivery PR.

## Decision

Extend [ADR 0016](0016-local-ci-runners.md) with two allowlisted controller slots to
reduce queued work. Each slot runs in a separate host process, registers one ephemeral
runner, and owns its Compose project, mutex, daemon, workspace, socket and data volumes.
Slot 1 retains `armoney-ci-buildmachine` and `ArMoneyLocalCiController` so existing
controllers remain compatible. Slot 2 uses `armoney-ci-buildmachine-slot2` and
`ArMoneyLocalCiControllerSlot2`. Arbitrary slot/project names and service scaling are
unsupported. Bounded and continuous rotations preserve the selected slot.

Each registration retains the trust preflight and token-erasure handshake. Competing
controllers and dirty state in the selected slot fail closed. Cleanup targets only
that project and exact generated runner registration; a cleanup failure stops its
rotation. No host socket, application volumes or host PAT enter either runner.

## Consequences

Slot 2 can be started from a separate reviewed checkout without interrupting an active
legacy slot 1. Active controller files must not be replaced between rotations.
Separate daemons and network namespaces permit unchanged inner smoke-test ports and
project names. Host compute, disk and network remain shared and can limit throughput.
Both slots accept the same trusted workflow labels; jobs are not pinned by kind.

The existing `armoney-ci-artifacts` volume remains shared and writable by both slots.
Current backend/Android archives have different names under each run/attempt; future
same-kind matrix jobs require unique paths. Neither artifacts nor privileged DinD
provide a security boundary between mutually untrusted jobs. Manual recovery selects
one stopped slot and never substitutes the application's persistent Compose project.

Mocked controller regressions cover slot selection, rotation, contention, trust and
cleanup failures without using live mutexes, Docker or GitHub. They supplement rather
than replace live two-slot isolation evidence and full disposable CI acceptance.
