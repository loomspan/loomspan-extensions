# TypeSafe provider contract

Primary documentation refreshed **2026-11-16** without inference or account access:

| Source | Documented behavior |
| --- | --- |
| [HTTP schema](https://docs.typesafe.ai/api/system-one.md) | POST `/v1/systemone`, bearer authentication, `model`, `state`, mixed `questions`, complete JSON response. |
| [Models](https://docs.typesafe.ai/models.md) | Jev 1.14.0 and 1.13.0; `jev-latest`/`jev-preview` currently point to 1.14.0. Default 64k request/32k question context; opt-in long context has separate constraints. |
| [Choice](https://docs.typesafe.ai/primitives/choice.md) | Option-keyed criteria, choice/probabilities/confidence. |
| [Noul](https://docs.typesafe.ai/primitives/noul.md) | Yes probability in `noul`; optional true/false criteria, no separate confidence. |
| [Score](https://docs.typesafe.ai/primitives/score.md) | Ordered criteria; score/confidence/legend and probabilities keyed by level index. |
| [Structured entries](https://docs.typesafe.ai/primitives/advanced.md) | Native instructions and criterion entries can be string/object/array/null. |
| [Errors](https://docs.typesafe.ai/api/errors.md) | UTF-8 finite-number JSON, 32 MiB request body, service nesting limits; 429/529 warrant backoff. |
| [OpenRouter TypeSafe guide](https://openrouter.ai/docs/guides/community/typesafe-sdk) | Base `https://openrouter.ai/api` appends `/v1/systemone`; bare or author-prefixed model IDs; response adds id/provider/usage.cost. |
| [SDK retries](https://docs.typesafe.ai/sdk/javascript/api/interfaces/RetryPolicy.md) | SDK has its own retry policy. This artifact uses no provider SDK and disables Apache automatic retries. |

The transport preserves prepared text/JSON requests containing Choice, Noul and
Score together, and returns complete JSON without schema enforcement, answer
extraction or usage accounting. It does not select models, bind questions, discover
providers or use OpenRouter's separate Decisions endpoint. Images/Box are outside
the implemented minimum. Request construction and capability validation belong
to a callable executor; this artifact currently contains the transport only.

The checked-in `src/test/resources/typesafe` fixtures are synthetic examples of
these documented shapes, not recorded inference or evidence of accuracy, latency,
cost, account access, route availability or model quality. Model aliases and route
availability may change. Configure a protocol-compatible route explicitly.
