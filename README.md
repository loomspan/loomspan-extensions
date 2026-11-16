# Loomspan Extensions

Independently versioned executor packs for Loomspan Framework.

This repository currently contains a Maven scaffold, not a working or published
extension. Start with [Loomspan Decisions](extensions/decisions/README.md).

## Local development

The starting build uses Java 21 and Maven 3.9 or later, matching the Framework's
current Java baseline. Validate all module descriptors from this directory:

```shell
mvn validate
```

The root POM aggregates local builds. Each extension owns its own POM and version
and can be built directly. No Framework or Spring Boot dependency has been selected
for the scaffold; supported runtime combinations will be recorded after integration.

## Planning source

The authoritative design and acceptance criteria remain in the sibling Framework
checkout's [extraction roadmap](../loomspan-framework/ai/thoughts/phases/executor-packs-and-typesafe-extraction-roadmap.md).
This is a local workspace link: keep `loomspan-framework` beside this repository
when working from that plan. Its companion documents own research and source evidence.
Do not copy the decision contracts into this repository.

## Layout

| Location | Purpose |
| --- | --- |
| `pom.xml` | Local reactor build |
| `extensions/decisions/pom.xml` | Independently versioned pack |
| `extensions/decisions/src/main/` | Pack implementation and resources |
| `extensions/decisions/src/test/` | Pack tests and deterministic provider fixtures |
| `extensions/decisions/examples/support-triage/` | Consuming example source, once implemented |

The pack README is the initial documentation index. Additional documentation and
tooling directories can be added as implemented behavior needs them.
