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
executor receives `loomspan.executor-config.default.typesafe`; supply this map even
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
input_schema:
  type: object
  properties:
    ticket: {type: string}
  required: [ticket]
  additionalProperties: false
input_transform:
  language: jslt
  expression: |
    {"model": "jev-latest", "state": .ticket,
     "questions": {"repeat": {"type": "noul",
                             "instructions": "Has this customer contacted support more than once?"}}}
```

Use an omitted or empty `config` map. The pack rejects every leaf configuration
key at invocation. The captured executor namespace map accepts two keys,
required `api_key` and optional `base_url`. Present null, blank or non-string settings and
malformed URLs fail before sending. An absent `base_url` defaults to
`https://api.typesafe.ai`. Valid credentials are used verbatim. A configured path
prefix stays in place when `/v1/systemone` is appended. See [transport](transport.md)
for header and URL constraints.

Applications supply **resolved values** in these maps, for example using
`SkillReloader.prepare` with an `ExecutionConfiguration` containing a JSON-encoded
map as YAML (see the [recipe Java](../examples/support-triage/src/main/java/ai/loomspan/examples/triage/TriageApplication.java)).
The pack has no environment fallback, `${...}` interpolation, `api-key-ref`
resolution or native model/connection lookup. Framework's native connection
credential map is a different facility and does not resolve executor map values.

## Native requests

A leaf with `input_transform` receives ordinary business input. Framework
validates the declared input schema before applying JSLT and hands the resulting
object to the pack. For direct native input, omit `input_transform` and pass the
complete native request as ordinary input. Both routes supply exactly `model`,
`state` and `questions` through `SkillExecutorInvocation.input()`. `model` is a
nonblank provider model ID. State is text, an object or an array. Question names
stay unchanged. Question objects contain `type`, optional `instructions` and
`criteria`, in the [provider's native forms](provider-contract.md).

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

Native input and structured descriptions preserve present nulls and finite
numbers, including BigDecimal and BigInteger values. The pack copies, validates
and serializes the complete request once per invocation. Retries reuse those
bytes. Cycles, unsupported Java values, nonfinite numbers, nesting above 128
and serialized requests above 32 MiB fail locally.

On the direct route, a value can contain a local Spring `ByteArrayResource`,
`ClassPathResource`, `FileSystemResource` or `PathResource`. The pack reads
content as UTF-8 text during request preparation. Other Resource types fail
before opening. Ordinary URL strings stay text. Choose `FileSystemResource`
for files because this Spring version deprecates `PathResource`. A leaf
that uses JSLT accepts only JSON values through Framework's transform.

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
