# ADR: Select checks by affected components

## Decision

Owner instruction on 2026-10-15 replaces unconditional full builds with
changed-component checks and their explicit dependency/acceptance closure.
The workflow selects from the exact PR head and merge-base diff, uses fixed
allowlisted outputs/tasks and falls back to all checks for unknown changes or
missing base history. Head mismatch fails closed. Public contracts and shared
runtime expand coverage; test-only changes stay scoped.

Stable job names record an explicit no-op when unaffected. Required checks and
Docker isolation are preserved. Full validation is available on manual dispatch;
CI/build/selector changes select it automatically. The redundant main-push suite
is removed; reviewed PR checks remain mandatory before merge.

## Consequences

Unrelated Android/Kafka/assistant builds no longer compete for heavy build slots.
The path/dependency table is maintained code and requires regression tests when
architecture changes. Integration fixtures may need unchanged service binaries.
No persistent data, deployment behavior, service contracts or money invariants
change. See [affected CI](../affected-ci.md) for limits and acceptance.
