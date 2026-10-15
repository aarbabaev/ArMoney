# Affected CI checks

Pull requests select checks from the complete merge-base-to-head Git diff. The
selector verifies the checked-out head and handles deletions and both sides of
renames. Missing history or unknown paths select the full suite; invalid head
provenance stops selection rather than producing green checks.

| Changed files | Selected work |
| --- | --- |
| Documentation only | Selector regression tests and explicit component no-op summaries |
| Android | Android unit tests, lint and APK |
| Web tests | Web build, unit and browser fixtures (when the web job is present) |
| Web runtime | Web checks and real browser/SSO acceptance |
| Java service tests | That module's `check`, including PostgreSQL integration tests |
| Java service runtime or migration | That module's `check` and linked cross-service acceptance |
| Shared Java runtime | All dependent Java module checks and related acceptance |
| Public API contracts | Service/dependent checks plus consuming clients |
| Kafka infrastructure | Kafka guards and isolated cluster acceptance |
| Dispatcher/dashboard | Queue, process and dashboard checks |
| Assistant tests | Assistant checks |
| Assistant runtime | Assistant checks and real gateway/provider-outage acceptance |
| CI, build tooling, unknown paths | Full suite |

Service code has no direct cross-service Java dependency. The shared
`platform-runtime` is a dependency of the service modules. Acceptance links are
explicit in `scripts/ci-impact.py`; update them when contracts or ownership change.

The existing required job names remain stable. An unaffected job checks out the
revision and records that no component build/tests were executed; it does not
report that omitted tests passed. These lightweight jobs still incur local runner
startup overhead. No branch protections are changed. A failed selector prevents
all dependent checks from succeeding.

There is no automatic duplicate CI run on `push` to main after a reviewed merge.
Use **Actions > CI > Run workflow** for a full release check. This does not replace
local deployment/readiness acceptance or authorize deployment. Direct main pushes
remain prohibited by repository delivery policy.

Scoped Java checks use a fixed module allowlist and `:module:check`, retaining
unit, architecture and real PostgreSQL tests. `clean` is no longer forced. When
cross-service acceptance needs a runnable bank, distribution/container builds for
otherwise unchanged services are still fixture prerequisites; their unrelated
unit/integration suites are not rerun. This is dependency work, not a claim of
zero compilation outside the changed directory. Fresh disposable runners do not
provide a persistent Gradle/Docker cache across jobs.

Selector/Gradle safety tests run on every PR. Full mode is also required when the
selector or workflow changes, so this optimization validates its own deployment
with the existing complete suite. iOS remains paused; emulator execution is never
part of these jobs.
