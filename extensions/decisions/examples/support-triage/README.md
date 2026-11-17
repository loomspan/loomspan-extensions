# Support triage

A runnable application consuming the installed
`ai.loomspan:loomspan-decisions:0.1.0-SNAPSHOT` JAR. The independent [POM](pom.xml)
uses Java 21, Boot 4.2.0-RC2 and Framework
`ai.loomspan:loomspan-spring-boot-starter:1.0.0-beta.10-SNAPSHOT` transitively.
Tested Framework source: `d992449e8eba0734c466de9a1f05526c840ba608`; the
[pack version table](../../README.md) lists exact library/tool versions.
These are unpublished local snapshots, not released artifacts.

Keep Framework beside Extensions. Install Framework first from its root:

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
Framework APIs. No implementation mocks, Console, real credentials or inference
are needed. All three commands establish the documented local proof.

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

The app owns Choice team criteria, Noul repeat instructions, Score impact levels,
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
TypeSafe leaf's native `config.request.model`. The harness publishes native parent
configuration with `api-key-ref` and its supplied credential map; that facility
does not resolve pack executor maps. Real parent use needs an authorized Framework
model connection. Keep business policy and criteria application-owned.

## Limits and evidence

See [executor configuration](../../docs/executor.md) for exact syntax. Native
Choice/Noul/Score work separately or mixed with text/JSON state, instructions and
criteria. Images/assets and Box fail before send. Validation runs each invocation;
publication success does not certify provider configuration. Bindings preserve
present null and omit missing object fields; score order remains authored.
Executor leaves have no generated output metadata or `output_schema`; generic
runtime binding checks remain. Use `/answers/...`, not answers-only paths.

Complete native results preserve unknown fields, refusals, usage, JSON null/string
and other values. Malformed/empty successful HTTP fails without decoding-only retry.
Diagnostic limits never truncate business results. YAML executor-config decimals
may already have rounded; the pack preserves received values and adds no precision
mode or extra rounding. Redirects are never followed.

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
the CLI's observability-disabled warning is expected. This suite establishes
normal canonical recording, not complete Console/MCP qualification.

- `skillConfig...`/`executorConfig...` errors indicate local validation. Check map
  presence, model/native shape and pointers; no stub request should have arrived.
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
exactly two resolved arguments, base URL then key:

```powershell
mvn -B -f extensions/decisions/examples/support-triage/pom.xml -DskipTests compile exec:java "-Dexec.args=https://api.typesafe.ai YOUR_RESOLVED_KEY"
```

This optional command performs real requests and was not used for verification.
Production hosts supply resolved credentials through application setup; these
arguments are a minimal local recipe. Compatible proxy prefixes are retained.
There is no discovery or automatic provider fallback.

## Reusing the harness

[ConsumerHarness](src/test/java/ai/loomspan/examples/triage/ConsumerHarness.java)
exposes real Boot startup, public publication/invocation/observer, HTTP stub queues,
captured requests and canonical file reading. Reuse these seams for publication,
concurrency, lifecycle, accounting, diagnostic-fault and Console/MCP qualification
without replacing the transport or rebuilding the application harness.
