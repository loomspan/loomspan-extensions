# Installed build verification

The Decisions pack and Framework use independently built local Maven snapshots.
Use the versions in the [pack version table](../README.md#tested-local-combination)
and keep their matching source checkouts beside each other. The Framework
starter requires pinned `ai.loomspan.thirdparty:jslt:0.1.15-jackson3-cf30e6d`.
Follow the [Framework JSLT build guide](../../../../loomspan-framework/docs/building-jslt.md)
before installing the starter. JSLT runs in Framework when a leaf declares
`input_transform`.

## Deterministic local checks

From Framework root, test the supported Java surface, transform and executor
declarations and configuration while installing the starter.

```powershell
./mvnw.cmd "-Dtest=LoomspanPublicSurfaceArchitectureTest,ExecutorInputTransformTest,ExecutorInputTransformIntegrationTest,ExecutorDeclarationTests,ExecutorConfigurationTests" install
```

From Extensions root, install the tested Decisions JAR before verifying the
independent consumer. Use the same Maven repository for both installs.

```powershell
mvn -B validate
mvn -B -f extensions/decisions/pom.xml install
mvn -B -f extensions/decisions/examples/support-triage/pom.xml verify
mvn -B -f extensions/decisions/examples/support-triage/pom.xml -DskipTests compile exec:java
```

The ordinary consumer suite uses normal Boot discovery, local HTTP stubs and
test credentials. Surefire skips `ConsoleConsumerTest` unless the Console
profile enables it. The demo prints the native request to the stub, the full
provider fixture and `Illustrative policy: route to returns; priority review`.

| Check | Evidence |
| --- | --- |
| Native input validation, numeric and null fidelity, local Resources, request limits and leaf config rejection | `TypeSafeRequestTest` |
| Captured namespace settings, default URL, prefix and exact credentials | `TypeSafeSettingsTest` |
| Real HTTP, retry bytes, redirects, cancellation, timeouts and diagnostics | `TypeSafeTransportTest`, `TypeSafeTransportLifecycleTest` and `TypeSafeTransportDiagnosticsTest` |
| Installed JAR discovery, transformed and direct native request equivalence, role enforcement, local rejection and complete JSON results | `TypeSafeConsumerTest` |
| Parent ticket and selected answer propagation, result forwarding and invalid bound values | `CompositionConsumerTest` on the actual recipe manifests |
| Concurrent old and new executor namespace endpoints and keys, retry bytes and generation retention | `PublicationConsumerTest` |
| Live deadlines, interruption, progressing body limits, physical work retention and shutdown | `LifecycleConsumerTest` |
| Native attempt quotas and usage, pack usage evidence, local decode failure and executor-only totals | `AccountingConsumerTest` |
| Diagnostic target and INFO/WARN faults with real pack success and failure | `DiagnosticFaultConsumerTest` |

Consumer probes receive the Framework invocation and delegate to the installed
pack. Lifecycle tests use its real deadline and cancellation observations.
Diagnostic tests inject failures in target acquisition or recording. The late
physical return test holds a completed pack result before releasing the
executor invocation so it can observe generation retention.

## Optional Console checks

Use the [Console profile in the recipe](../examples/support-triage/README.md#repeatable-console-inspection)
with an executable built from the matching Framework checkout. The profile
starts a local Console process, imports the canonical pack traces and exercises
the existing browser/MCP record and content routes. The ordinary Framework and
consumer builds do not exercise those routes.

The [provider sources and synthetic fixtures](provider-contract.md) describe
wire shapes and model-route context. Use an authorized provider account to
evaluate actual availability, quality, latency and cost.
