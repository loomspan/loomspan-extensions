# Loomspan Extensions repository guidance

Read the root README's [planning source](README.md#planning-source) before deriving
tickets or implementing the extraction. Follow the authoritative roadmap and its
ticket handoff requirements; update decisions at their owning location instead of
restating them here. Read the Framework checkout's `AGENTS.md` for its public API
and content-fidelity policies when integrating with it.

Keep implementation, tests, examples, and release documentation owned by the relevant
extension. The root POM is an aggregator, not a shared release parent.

For scaffold-only changes, run `mvn validate` at the root and directly against each
changed extension POM. Implementation work must add meaningful repository-local
checks as specified by its owning ticket. A successful descriptor validation is not
evidence that the executor or consuming example works.
