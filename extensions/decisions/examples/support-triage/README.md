# Support triage

A runnable application consuming the installed
`ai.loomspan:loomspan-decisions:0.1.0-SNAPSHOT` JAR. The independent [POM](pom.xml)
uses Java 21, Boot 4.2.0-RC2 and Framework
`ai.loomspan:loomspan-spring-boot-starter:1.0.0-beta.10-SNAPSHOT` transitively.
The [pack version table](../../README.md) lists exact library/tool versions.
These are unpublished local snapshots, not released artifacts.

Keep Framework beside Extensions. Follow the
[pack prerequisites](../../README.md#local-build) to install pinned JSLT, then
install Framework from its root.

```powershell
./mvnw.cmd -DskipTests install
```

Then from Extensions root:

```powershell
mvn -B -f extensions/decisions/pom.xml install
mvn -B -f extensions/decisions/examples/support-triage/pom.xml verify
mvn -B -f extensions/decisions/examples/support-triage/pom.xml -DskipTests compile exec:java
```

Pack `verify` alone is not consumer verification. The independent consumer asserts
its dependency comes from a JAR, using normal Boot discovery and only supported
Framework APIs. Functional cases use the real executor and transport; advanced
fault/lifetime probes are explicitly test instrumentation around that same pack.
Console, real credentials and inference are not needed. All three commands establish
the documented local proof. Exact artifacts and advanced evidence are in
[tested builds](../../docs/tested-builds.md).

## What runs

[TriageApplication.java](src/main/java/ai/loomspan/examples/triage/TriageApplication.java)
starts a local JDK HTTP stub on an ephemeral loopback port with `local-test-key`.
It publishes [triage.yaml](src/main/resources/triage.yaml) using `SkillReloader`,
with application-resolved settings in `ExecutionConfiguration`, then calls
`SkillTemplate`. [application.yaml](src/main/resources/application.yaml) supplies
the selected empty `loomspan.executor-config.default.typesafe` startup map.
Installing the dependency creates reference-only `default-typesafe`, while the
application explicitly declares the callable skill. Executor maps have no implicit
environment fallback, `${...}` interpolation or native credential lookup.

Default input:

```json
{"ticket":{"message":"Parcel arrived damaged and is unusable","contacts":2}}
```

The triage JSLT expression builds the request from the ticket and its authored model,
Choice team criteria, Noul repeat instructions and Score impact levels. The app owns the
input schema, `rbac_roles: [SUPPORT_TRIAGE]` and action policy. The demo installs a
local caller with `ROLE_SUPPORT_TRIAGE`; real hosts authenticate callers and grant
roles themselves. Consumer tests check unauthorized rejection. HTTP/retry logic
stays reusable in the Java pack.

The deterministic fixture is illustrative, not inference. Abbreviated result:

```json
{"answers":{"team":{"choice":"returns","confidence":0.92},"repeat":{"noul":0.9},"impact":{"score":1.8}},"usage":{"input_tokens":120,"output_tokens":12}}
```

The actual printed native result retains the full model, answers, probabilities,
legend, usage and unknown `fixture` field. The app prints:

```text
Illustrative policy: route to returns; priority review
```

Application Java handles refusals and missing/low-confidence routing with manual
review. Execution errors are caught, reported for manual review and propagated.
Confidence 0.8 and impact 1.5 are illustrative thresholds requiring evaluation on
your data. The pack never enforces them.

## Composition

[parent.yaml](src/main/resources/parent.yaml) composes triage with
[assess.yaml](src/main/resources/assess.yaml). It binds parent `/ticket` to triage,
then binds `/answers/repeat/noul` from triage's complete native result to assess's
numeric `/probability`. `output_from` forwards the complete assess JSON text.
[CompositionConsumerTest](src/test/java/ai/loomspan/examples/triage/CompositionConsumerTest.java)
executes these files against a deterministic local model supplying a two-task
plan, with assess depending on triage. Fully bound closed child schemas allow
automatic assigned dispatch. Missing/type-invalid native result sources fail
ordinary runtime binding checks before assess sends.

The parent model alias `local` is a Framework connection, distinct from the
TypeSafe leaf's native `model` in its transform. The assess transform uses the
bound probability as part of its native state. The harness publishes native parent
configuration with `api-key-ref` and its supplied credential map; that facility
does not resolve pack executor maps. Real parent use needs an authorized Framework
model connection. Keep business policy and criteria application-owned.

## Limits and evidence

See [executor configuration](../../docs/executor.md) for exact syntax. Native
Choice/Noul/Score work separately or mixed with text/JSON state, instructions and
criteria. Images/assets and Box fail before send. On each invocation,
the pack checks settings and native input before sending. The consumer suite proves
that the triage transform and equivalent direct native input send the same request.
Executor leaves have no generated output metadata or `output_schema`; generic
runtime binding checks remain. Use `/answers/...`, not answers-only paths.

Complete native results preserve unknown fields, refusals, usage, JSON null/string
and other values. Malformed/empty successful HTTP fails without decoding-only retry.
Diagnostic limits apply to trace content. The pack preserves the complete result
and received JSON numbers. Redirect responses fail without following the location.

The demo prints a trace session ID. Its retained canonical
`<session>.<trace>.execution-trace.ndjson` is under the JVM temporary directory.
Tests set that directory to this example's `target/traces` and inspect actual files
for normal request, response and caught retry evidence. Original source values and
explicit capture states remain recorded. Large diagnostic data can be unavailable
with a system notice while the complete business result is still returned.

Diagnostics are records within existing frames (`EXECUTOR_DIAGNOSTIC_RECORDED`),
with message/data in the existing `data` path, INFO/WARN levels and recorded capture
states. Framework system notices use `EXECUTOR_DIAGNOSTIC_SYSTEM_NOTICE_RECORDED`
without developer severity. Absence alone explains no capture-failure cause; a
complete notice cannot recover discarded evidence. The derived observer journal
retains its existing field-name projection exception; use canonical files for
source fidelity. Developer error severity does not itself create execution failure.

Optional matching Console inspection reuses `LOOMSPAN_query_trace_records` and
`LOOMSPAN_read_trace_content`, normal type/frame filters and bounded content
references. No diagnostic-only frame, stream, tool, store, index, cursor or content
protocol exists. A connected servlet host or portable retained trace is needed;
the CLI's observability-disabled warning is expected.

### Repeatable Console inspection

Build the matching Console from `loomspan-framework/loomspan-console` with its
exact pinned toolchain (Go 1.26.5, Node 24.18.0, npm 12.0.2):

```powershell
go run ./internal/buildtool build
```

The optional profile runs the ordinary suite and real Console browser/MCP
checks from the Extensions root. This Windows command uses the sibling checkout.

```powershell
mvn -B -f extensions/decisions/examples/support-triage/pom.xml -Pconsole "-Dloomspan.console.executable=$((Resolve-Path ../loomspan-framework/loomspan-console/build/loomspan-console.exe).Path)" verify
```

On other platforms pass the canonical native `build/loomspan-console` executable.
The profile fails when the executable is missing or its version mismatches. It
starts an isolated loopback process without a browser window, pairs normally,
enables MCP and imports the actual retained pack traces without altering their
compatibility marker. Windows profile/workspace directories use the current user's
`TEMP` parent so Console can enforce owner/DACL protection; the checkout may have
shared inherited permissions. Process logs remain under `target/loomspan-console-consumer-*.log`.
Each process is stopped and HTTP clients close after the test.

Expected evidence includes request/response, caught HTTP failure/retry, authored
messages/severity, explicit message truncation/data unavailability and notices
without developer severity. Existing tools traverse logical/physical records,
ordinary type/frame filters, bounded pages, inline content and retained byte ranges.
The mixed mission shows only two Framework model attempts and ten accounted units,
despite the pack's retry and large unknown native usage. The test's authored ERROR
diagnostic alone leaves its successful trace successful.

For interactive inspection, start the matching executable, open its printed pairing
URL, import the CLI's retained NDJSON in the Artifacts screen, and inspect its records.
Enable MCP in Console before configuring a client with the shown endpoint/credential.
If import fails, check the exact matching Console version and that the trace is finalized;
never edit its marker. If capture is unavailable, inspect the recorded notice/state;
discarded evidence is not recoverable and absence does not establish a cause.

- `input.*` errors identify invalid native request fields. `executorConfig.*` errors
  identify invalid namespace settings. `skillConfig.*` errors identify leaf keys
  that must be removed. These checks run before sending.
- HTTP/IO failures record caught failure/retry evidence, with at most three identical
  bodies. HTML/malformed errors do not remove HTTP retry eligibility.
- Missing native fields or unconventional confidence values are application data;
  the pack does not enforce a provider answer schema.
- Framework-accounted usage excludes executor requests/retries and diagnostic usage.
  Zero does not mean measured zero executor usage; missing native usage stays missing.

## Optional live setup

Create a TypeSafe account/key and select a model/route available to that account.
The authored `jev-latest` is an example, not an availability guarantee. Consult the
[dated provider sources](../../docs/provider-contract.md). The application accepts
exactly two resolved application arguments, base URL then key, through Maven's
`exec.args` option. Supplying those arguments performs real requests and was not
used for verification; all commands above use local test credentials.
Production hosts supply resolved credentials through application setup; these
arguments are a minimal local recipe. Compatible proxy prefixes are retained.
There is no discovery or automatic provider fallback.

## Reusing the harness

[ConsumerHarness](src/test/java/ai/loomspan/examples/triage/ConsumerHarness.java)
exposes real Boot startup, public publication/invocation/observer, HTTP stub queues,
captured requests and canonical file reading. Reuse these seams for publication,
concurrency, lifecycle, accounting, diagnostic-fault and Console/MCP qualification
without replacing the transport or rebuilding the application harness.
