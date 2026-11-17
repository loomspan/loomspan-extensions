# Tested builds and deterministic verification

This local qualification uses the native-free Framework and independently installed
Decisions JAR. Nothing is publicly published. The combination is development evidence,
not a promise of service availability, release compatibility, live quality or cost.

## Exact combination

| Component | Tested identity |
| --- | --- |
| Framework dependency | `ai.loomspan:loomspan-spring-boot-starter:1.0.0-beta.10-SNAPSHOT` |
| Framework/Console production source | `fa1ca190d393b63314846e604a6bbd8157ed8eab` |
| Decisions dependency | `ai.loomspan:loomspan-decisions:0.1.0-SNAPSHOT` |
| Decisions production source | `4a52c709080ecff70ca363aacad9a02ee707666f` |
| Qualification source | Consumer source fingerprint below; added tests/POM in this revision |
| Invocation contract | Exact `ai.loomspan.api.extensions.v1.SkillExecutorInvocation` and `ExecutorDiagnostics` |
| Console/compatibility marker | `1.0.0-beta.10-SNAPSHOT`, unchanged, exact matching builds |
| Runtime | Windows 11 amd64; JetBrains Java 21.0.2; Spring Boot 4.2.0-RC2 |
| Dependencies | Apache HttpClient 5.6.5; Jackson 3.1.8; JUnit Jupiter 6.1.3; MockWebServer 4.12.0 |
| Build tools | Maven 3.9.6; Framework wrapper Maven 3.9.11; Console Go 1.26.5, Node 24.18.0, npm 12.0.2 |

Tested production artifact SHA-256 values:

| Artifact | SHA-256 |
| --- | --- |
| Framework starter JAR | `3f4bd57de312f65012d717ba987dcfa8dba4fc9902206c4949413bc6e7fe52eb` |
| Decisions JAR | `5b93cbf94ca0daca7d4ce0cb767dd5993d96f66ceb7bf17f64b5d9900490be6e` |
| Windows Console executable | `2c897cc1fb76379daf0268ae5de773fefa73362b6a61f0c13d49da3e64dfa68c` |

The production source is unchanged by qualification. Framework docs and the consumer
tests/POM are updated here. The consumer source fingerprint is
`f677b1d099275c0cbb98a55a99d0102f9e399a418d56d3b5625b0c836f09cc89`.
It covers the example's POM and all 15 files below `src/`: sort relative paths
ordinally, hash each file's bytes with SHA-256, then hash UTF-8 lines
`relative/path<TAB>lowercase-file-hash<LF>` with SHA-256. This excludes docs, generated
outputs and commit metadata, avoiding a self-referential commit identity. Rebuilt
JAR/Console bytes may differ with build metadata; compare source and actual artifacts.

## Repeatable local commands

From the matching Framework root:

```powershell
./mvnw.cmd test
./mvnw.cmd -DskipTests install
./mvnw.cmd -Dtest=ConsoleTraceFixtureCorpusTest -DfailIfNoTests=false test
```

From Framework's `loomspan-console`, with its exact pinned tools on PATH:

```powershell
go run ./internal/buildtool build
```

The canonical build executes the same checks as `verify`, including typecheck,
604 frontend tests/coverage, asset verification and `go test ./...`, then stamps the
binary with the direct Framework POM version. No alternate version flag is used.

From Extensions root:

```powershell
mvn -B validate
mvn -B -f extensions/decisions/pom.xml verify
mvn -B -f extensions/decisions/pom.xml -DskipTests install
mvn -B -f extensions/decisions/examples/support-triage/pom.xml verify
mvn -B -f extensions/decisions/examples/support-triage/pom.xml -Pconsole "-Dloomspan.console.executable=$((Resolve-Path ../loomspan-framework/loomspan-console/build/loomspan-console.exe).Path)" verify
mvn -B -f extensions/decisions/examples/support-triage/pom.xml -DskipTests compile exec:java
git diff --check
```

Use the matching native Console executable on other platforms. The ordinary suite
runs 17 tests with the Console-only test excluded by its explicit condition; the
Console profile runs all 18 and must fail when no usable executable is supplied.
No real account, credentials or inference is used. The runnable recipe prints the
complete native fixture and `Illustrative policy: route to returns; priority review`.

## Named evidence and limits

| Check | Evidence |
| --- | --- |
| Pack behavior | Pack `verify`: 77 tests, zero failures/errors/skips |
| Registration, individual/mixed question types, numeric fidelity, validation, null/string/unknown response fields, redirects/retries | `TypeSafeConsumerTest`, installed JAR via Boot, real local HTTP |
| Child composition and `/answers/...` runtime bindings | `CompositionConsumerTest`, actual recipe YAML |
| Simultaneous distinct endpoint/key work, executor-owned and skill-owned publication, old retries/new admissions and shared transport | `PublicationConsumerTest` |
| Before-send and retry-wait cancellation/interruption, original deadline/zero expiry, progressing body after parent budget, retained late physical return/fencing, shutdown | `LifecycleConsumerTest` |
| Exact two native attempt quota, native reported/heuristic usage, unknown or missing pack usage, decode failure and zero executor-only totals | `AccountingConsumerTest` |
| Diagnostic-target and every used pack record level (INFO/WARN) faults preserve actual retry/success and HTTP failure | `DiagnosticFaultConsumerTest` |
| Actual import, thirteen tools, type/frame selection, logical/physical continuation, inline/ranged content, Unicode truncation, unavailable data/notices, authored ERROR without failure and mixed totals | `ConsoleConsumerTest`, real versioned process and protected browser/MCP routes |
| Generic API/lifecycle/accounting/diagnostic containment and bindings | Framework full suite: 2,226 tests, zero failures/errors; two unchanged opt-in diagnostic skips |
| Current Java/Go trace coherence | `ConsoleTraceFixtureCorpusTest`, 18 cases; REST corpus in Framework full suite |
| Executable recipe | `TriageApplication`, deterministic stub without Console |

Instrumentation is limited to test-owned probes. The probe receives the actual
Framework invocation and discovers/delegates to the installed pack's annotated
method. Lifecycle suppliers remain real. For diagnostic faults only, a Mockito
delegate forwards real config/live accessors while throwing at the diagnostic
target or record method; the pack itself uses INFO/WARN, so ERROR/recorder/notice/
fallback containment remains verified by Framework's diagnostic tests. For late
physical return, the probe holds a completed real pack result before Java returns;
this verifies retained local work, not remote inference cancellation.

Console starts in an isolated user-owned temp profile/workspace, with logs under
the example's `target/`. It pairs, enables MCP and imports finalized canonical
pack files without changing their marker. The client closes and process terminates
after testing. Portable import qualifies actual recording/query/content consumption;
it does not introduce an application HTTP or live-tail contract.

Business serialization and caller argument evaluation are outside new diagnostic
containment; automatic observation keeps its existing behavior. Framework's fixed
cleanup grace is separate from the live execution budget. Physical-return probes
observe zero remaining time while work is still retained, then prove retirement
only after release. Cancellation does not promise immediate remote inference or
billing cancellation. Provider usage remains source evidence, never an addition to
Framework totals or a fabricated measured zero.

The dated [provider refresh](provider-contract.md) and synthetic fixtures establish
wire shapes/model-route context only. Public publication, live quality/calibration/
latency/cost evaluation, the sidecar suite, Gemini/OpenAI and images/Box are future
work. Repository maintainers own support; see the [pack index](../README.md).
