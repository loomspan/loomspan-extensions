# Transport settings and behavior

One Spring-created transport owns one Apache pool (100 total connections, 20 per
route), client and deadline watchdog. Endpoint, authorization and timeouts are
request-local; no authenticated client cache or generation-owned pool exists.
Spring closes the transport at application shutdown. Close is idempotent, rejects
new sends, closes physical sockets and terminates the watchdog. HTTP runs on the
invocation thread; the watchdog does no I/O. There are no background I/O workers.

## Captured settings

The transport reads the invocation's captured skill/executor maps once per send.
Only these two keys use the following presence-based precedence:

| Setting | First | When absent | Absent from both |
| --- | --- | --- | --- |
| `api_key` | `skillConfig.api_key` | `executorConfig.api_key` | Fail before send |
| `base_url` | `skillConfig.base_url` | `executorConfig.base_url` | `https://api.typesafe.ai` |

Present null, blank or non-string values fail at the selected path without falling
back. Keys retain their exact valid ASCII HTTP-header value; controls/non-ASCII
header characters fail rather than being repaired. Spaces in nonblank credentials
are not trimmed. Applications supply resolved values: no environment lookup,
`${...}` interpolation, `api-key-ref` or native connection lookup occurs.

Base URLs must be absolute HTTP(S) URLs with a host, valid optional port and optional
path, without userinfo, query, fragment or surrounding whitespace. Trailing slash
separators are removed before appending `/v1/systemone`; encoded prefixes remain
intact. For example `https://proxy.example/api/` targets
`https://proxy.example/api/v1/systemone`. Each request is POST JSON with
`Authorization: Bearer <exact selected key>`. Separate invocations can use different
keys/endpoints concurrently on the same pool. Publishing each configuration owner
uses Framework's existing capture/retention semantics; retries retain selected values.

## Deadlines and retries

Fixed pack defaults are:

| Control | Default |
| --- | --- |
| Total per physical attempt, including pool wait/connect/headers/body | 60 seconds |
| Socket inactivity | 30 seconds |
| Pool acquisition | 5 seconds |
| Connect | 10 seconds |
| Maximum physical sends | 3 (initial plus 2 retries) |
| Retry backoff | 250 ms, then 500 ms |
| Retry-After cap | 5 seconds |
| Watchdog/retry-wait polling interval | At most 25 ms |
| Error body capture | 64 KiB UTF-8 prefix |

These controls have no YAML/property customization keys. Each attempt is capped by
live remaining execution time, and an earlier cap is never extended by later reads.
No deadline means the pack total still applies. Expired/cancelled/interrupted work
cannot send or retry; retry waits cooperate and preserve interruption. Progressing
bodies remain subject to the total deadline. Local socket cancellation is best
effort and does not promise immediate remote inference or billing cancellation.
Framework remains the execution/cancellation/fencing authority.

Only transport IO failures/request timeouts and HTTP 408/429/500/502/503/504/529 are
retry eligible while execution remains live. Valid nonnegative Retry-After seconds
or RFC-1123 dates override backoff up to the cap; invalid values use backoff. The
underlying Apache automatic retry layer is disabled; no provider SDK is used.
Requests are copied once and retries send identical body bytes. There is no remote
deduplication guarantee. Local configuration errors and successful JSON decoding
errors do not retry. All redirects fail as ordinary HTTP errors: Location is never
followed and its target receives no request or credentials.

Status is classified before best-effort error capture. Malformed/HTML bodies,
truncation or read failure cannot change known status eligibility. Error evidence
explicitly records `truncated`, `unavailable` and `captureLimitBytes`; incomplete
streams are cancelled before close to avoid draining an unbounded remainder.

## Results and diagnostics

Success returns the complete native JSON value, including envelope, unknown fields,
refusals, null, strings, arrays and usage. There is no provider answer-schema or
confidence enforcement, answers extraction or native usage/attempt accounting.
Success bodies have no diagnostic/error byte ceiling. Large integer/decimal values
are decoded as BigInteger/BigDecimal. Empty, malformed or trailing-token success
bodies fail after one send. JSON null is valid and distinct from an empty body.
The pack executor serializes the complete decoded value to JSON text before
returning through Framework's ordinary Java String passthrough, preserving JSON
null and strings without changing Framework conversion semantics.

Request/response/caught failure/retry evidence uses invocation-scoped info/warn
diagnostics with supported ordinary JSON values. New diagnostic preparation and
recording RuntimeExceptions are contained without changing results/original failures.
Framework's existing automatic observations keep their current failure semantics.
Business construction/serialization, caller expressions evaluated before diagnostic
method entry, and JVM-wide failures remain outside the diagnostic guarantee.
Framework capture bounds are independent of complete business results. Usage stays
evidence only. Content is preserved; no sensitivity classification or rewriting occurs.
