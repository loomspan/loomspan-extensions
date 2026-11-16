# Loomspan Decisions

Status: repository scaffold only. The executor, auto-configuration, and consuming
example are not implemented. Nothing has been published.

Initial unpublished coordinates: `ai.loomspan:loomspan-decisions:0.1.0-SNAPSHOT`.
The pack owns this version independently of the root aggregator. Publication and
support policy remain to be finalized; these are not installation instructions for
an available dependency.

## Development

From the repository root:

```shell
mvn -f extensions/decisions/pom.xml validate
```

This validates the descriptor only. The scaffold targets Java 21; no Framework/Boot
runtime combination has been tested. Add the identified Framework dependency when
its supported versioned invocation contract is implemented.

## Code and example ownership

- `src/main/java` and `src/main/resources`: pack implementation and registration resources.
- `src/test/java` and `src/test/resources`: tests and deterministic provider fixtures.
- [Support-triage example](examples/support-triage/README.md): application source and
  repeatable integration instructions when implemented.
- [Changelog](CHANGELOG.md): changes to this pack.

Design decisions and acceptance requirements live in the root README's
[planning source](../../README.md#planning-source). Configuration, result, Console,
and troubleshooting documentation will be added alongside the implemented behavior.
