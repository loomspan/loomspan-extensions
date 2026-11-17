# TypeSafe executor

Install the unpublished local `ai.loomspan:loomspan-decisions:0.1.0-SNAPSHOT` artifact
as described in the [pack README](../README.md). It targets the supported
`ai.loomspan.api.extensions.v1.SkillExecutorInvocation` contract. Spring Boot
auto-configuration registers reference-only `default-typesafe`; dependency
installation creates no callable skills. Java classes under the pack's internal
and autoconfigure packages are construction machinery, not supported extension APIs.

Applications declare ordinary `executor: typesafe` YAML leaves in `default` and
invoke their names through `SkillTemplate`. Existing namespace visibility,
configuration ownership, publication and authorization rules still apply. The
executor selects `loomspan.executor-config.default.typesafe`; supply this map even
if empty. Use a YAML Config Data source or public namespace publication for these
raw maps, not flattened Spring property names that change arbitrary map keys.

```yaml
loomspan:
  executor-config:
    default:
      typesafe:
        api_key: local-test-key
        base_url: http://127.0.0.1:8089/proxy
```

```yaml
name: repeatContact
description: Judge whether this is a repeat contact
executor: typesafe
config:
  request:
    model: jev-latest
    state: null
    questions:
      repeat:
        type: noul
        instructions: Has this customer contacted support more than once?
  bindings:
    /state: /ticket
```

`config` accepts `request`, optional `bindings`, and optional `api_key`/`base_url`.
Executor namespace config accepts `api_key`/`base_url`. A present skill setting
wins over the namespace setting. Present null, blank, non-string credentials/URLs
and malformed URLs fail before send, without falling back. If both maps omit the
key, credentials fail and the base URL defaults to `https://api.typesafe.ai`.
Valid credential content is preserved. Prefixes remain when `/v1/systemone` is
appended. See [transport](transport.md) for header/URL constraints and fixed limits.

Applications supply **resolved values** in these maps, for example using
`SkillReloader.prepare` with an `ExecutionConfiguration` containing a JSON-encoded
map as YAML (see the [recipe Java](../examples/support-triage/src/main/java/ai/loomspan/examples/triage/TriageApplication.java)).
The pack has no environment fallback, `${...}` interpolation, `api-key-ref`
resolution or native model/connection lookup. Framework's native connection
credential map is a different facility and does not resolve executor map values.

## Native requests and binding

`request` is a native object containing exactly `model`, `state`, `questions`.
`model` is explicit nonblank text, not a Framework alias. State is text, object or
array. Question names stay unchanged. Question objects contain `type`, optional
`instructions` and `criteria`, in the [provider's native forms](provider-contract.md).

| Type | Criteria |
| --- | --- |
| `choice` | Object with 1–255 nonblank option names; values text/object/array/null |
| `noul` | Optional null or object with `"true"`/`"false"` keys and text/object/array/null entries; instructions or criteria must contain descriptive content |
| `score` | Array of 1–10 text/object/array levels, in authored score order |

Instructions accept text/object/array/null. JSON content within state and structured
descriptions remains authored data; domain validation does not inspect arbitrary
data keys to infer capabilities. Images/assets, Box and unknown request/question
fields fail before send. Mixed question types use one request; there is no splitting,
discovery or fallback. Provider/model availability requires an appropriate account
and route; deterministic tests establish integration rather than quality or access.

`bindings` maps request-target JSON Pointers to input-source JSON Pointers.
`""` selects the whole input only on the source side. Escape `/` as `~1` and `~` as
`~0`. Targets require existing parents; an object leaf can be added or replaced,
and existing array elements can be replaced. Array indices use canonical unsigned
decimal notation. Malformed pointers, scalar/missing target parents, invalid array
indices and ancestor/descendant target overlaps fail with the configuration path.
There is no interpolation or legacy expression syntax.

A present input null is preserved. A missing source removes its target object
property. Missing-source array omission fails rather than shifting ordered values;
removing a required native field fails resolved validation. Captured maps and inputs
are not mutated. Build, validate and serialize once, then reuse identical retry bytes.

Finite JSON numbers are preserved as received. Framework's ordinary executor-config
YAML decoding can already round precise decimal literals; the pack cannot recover
those digits and adds no rounding, string conversion or precision mode. Input values
such as BigDecimal/BigInteger and native response numbers retain their received values.
Cycles, unsupported Java values, non-finite numbers, nesting above 128 and serialized
requests above 32 MiB fail locally. These are resource/representation limits.

Local Spring `ByteArrayResource`, `ClassPathResource`, `FileSystemResource` and
`PathResource` content may be supplied in request values, including through bindings.
Content becomes UTF-8 text during construction and is not reread on retries; it is
not implicitly parsed as JSON. Other Resource types fail before opening. A plain
URL string stays text and is never fetched by the pack. PathResource is supported
by this tested Spring version but deprecated there; prefer FileSystemResource.

## Results, evidence and lifecycle

All successful native JSON is returned as JSON text through the ordinary Java
executor path: full envelopes, unknown fields, refusals, usage, arrays/scalars,
JSON null and quoted strings. No answer extraction, wrapper, generated output
schema, answer/completeness/confidence enforcement or executor `output_schema`
support is added. Consumer bindings use `/answers/...`. Generic Framework runtime
binding checks still apply. Empty/malformed successful JSON fails after one send;
successful-response decoding does not cause a retry. Diagnostic bounds cannot
truncate business results. Business construction/serialization can fail execution.

The existing shared transport performs at most three attempts with fixed bounded
backoff, disables Apache retries/redirects, and checks live cancellation/deadlines.
See [transport](transport.md) for the total versus inactivity bounds and accepted
cooperative cancellation contract. HTTP failures remain execution failures, not
successful JSON responses. Validation occurs each invocation, never at startup or
publication; publication success does not certify provider configuration.

Request, complete response, caught HTTP/IO failure and scheduled retry evidence use
the existing invocation diagnostic facility. They are records within existing
frames (`EXECUTOR_DIAGNOSTIC_RECORDED`), with source content in `data`, developer
severity and explicit message/data capture states. Framework system notices use
`EXECUTOR_DIAGNOSTIC_SYSTEM_NOTICE_RECORDED` without developer severity. Absence of
evidence alone does not establish a capture-failure cause. A notice cannot recover
discarded content. New diagnostic capture failures are contained; existing automatic
observation and business serialization keep their existing semantics.

Optional Console inspection reuses `LOOMSPAN_query_trace_records` and
`LOOMSPAN_read_trace_content`; no separate diagnostic frame, stream, tool, store,
index, cursor, filter or content protocol is added. Developer error severity does
not create an automatic execution failure. Canonical trace content is preserved;
the derived observer journal retains its existing field-name projection behavior.

Framework-accounted usage covers Framework-native model calls. Pack requests/retries
do not reserve native provider-attempt quota; native usage stays in returned JSON
and recorded diagnostics without contributing to authoritative Framework totals.
Zero Framework-accounted usage does not establish measured zero executor usage.
Missing native usage is not substituted with zero. Applications own interpretation.
