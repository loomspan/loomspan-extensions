# Loomspan Decisions

TypeSafe Choice, Noul and Score through Loomspan's v1 executor invocation contract.
Installation registers the reference-only `default-typesafe` executor. Applications
declare callable YAML skills and own their questions, criteria and policy. Shared
HTTP connections, bounded retries/deadlines, complete native JSON results and
diagnostics run without Console. Internal Java
helpers and auto-configuration beans are not an application customization API.
Nothing has been published.

Unpublished coordinates: `ai.loomspan:loomspan-decisions:0.1.0-SNAPSHOT`.
The root POM only aggregates local builds; this artifact owns its version.

## Documentation

- [Executor configuration and binding](docs/executor.md).
- [Runnable support-triage recipe](examples/support-triage/README.md).
- [Transport settings and behavior](docs/transport.md): credentials, endpoint
  prefixes, fixed limits, retries, lifecycle, diagnostics and result semantics.
- [Provider contract and sources](docs/provider-contract.md): dated TypeSafe/model/
  route evidence and synthetic fixtures.
- [Tested builds and verification](docs/tested-builds.md): final native-free build
  identities, advanced consumer/Console commands and evidence boundaries.
- [Changelog](CHANGELOG.md).

## Tested local combination

| Component | Version |
| --- | --- |
| Java | 21.0.2, JetBrains runtime |
| Framework | `ai.loomspan:loomspan-spring-boot-starter:1.0.0-beta.10-SNAPSHOT`, native-free source `fa1ca190d393b63314846e604a6bbd8157ed8eab` |
| Console | `1.0.0-beta.10-SNAPSHOT`, same Framework source; Go 1.26.5, Node 24.18.0, npm 12.0.2 |
| Extension contract | `ai.loomspan.api.extensions.v1.SkillExecutorInvocation` and `ExecutorDiagnostics` |
| Spring Boot | 4.2.0-RC2 |
| Apache HttpClient | 5.6.5 |
| Jackson databind | 3.1.8 (`tools.jackson`) |
| Tests | JUnit Jupiter 6.1.3, MockWebServer 4.12.0 |
| Maven | 3.9.6; Framework wrapper 3.9.11 |

These are tested local snapshots/prereleases, not released compatibility promises.
Framework's existing configuration numeric decoding applies before pack execution;
high-precision declaration decimals may already have rounded. The transport
preserves prepared bytes and received JSON numbers without adding rounding.

## Local build

Keep `loomspan-framework` beside this repository. Test the matching Framework
checkout and install its dependency locally from that checkout:

```powershell
./mvnw.cmd test
./mvnw.cmd -DskipTests install
```

Then from this repository root:

```powershell
mvn -B validate
mvn -B -f extensions/decisions/pom.xml verify
mvn -B -f extensions/decisions/pom.xml install
mvn -B -f extensions/decisions/examples/support-triage/pom.xml verify
mvn -B -f extensions/decisions/pom.xml dependency:tree
```

Pack `verify` runs unit, Spring and real local HTTP tests through Surefire. The
separate example `verify` is also required: it exercises the installed JAR through
normal Boot discovery and supported Framework APIs, including parent composition.
It
requires no provider credentials, account access, live inference or Console.
Installing the pack does not expose a callable skill. Applications supply
resolved provider settings; the transport never finds environment credentials or
native model connections.

## Maintenance and support

The Decisions repository maintainers own this artifact, its provider integration,
recipe and documentation; Framework maintainers own generic execution and Console.
Report reproducible integration issues with the tested build identities and local
fixture. Fixes arrive through a pack dependency update. These unpublished development
snapshots have no promised release cadence, support SLA or provider service guarantee.
Public Maven publication and product releases are separate maintainer decisions.
TypeSafe is the v1 scope; Gemini/OpenAI, images/Box, live quality/latency/cost evaluation,
catalog infrastructure and the sidecar suite remain future work.
