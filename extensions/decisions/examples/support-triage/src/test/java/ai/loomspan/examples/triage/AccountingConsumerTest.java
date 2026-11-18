package ai.loomspan.examples.triage;

import ai.loomspan.api.SkillDocument;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static ai.loomspan.examples.triage.ConsumerHarness.*;

class AccountingConsumerTest {
    static Map<String,Object> functionCall() {
        return Map.of("id", "fc_child", "type", "function_call", "call_id", "child", "name", "default-decision", "arguments", "{}", "status", "completed");
    }
    static Map<String,Object> textItem(String text) {
        return Map.of("id", "msg_local", "type", "message", "role", "assistant", "status", "completed",
                "content", List.of(Map.of("type", "output_text", "text", text, "annotations", List.of())));
    }
    static MockResponse nativeResponse(List<?> output, boolean missingUsage) {
        var body = new LinkedHashMap<String,Object>(Map.of("id", "fixture", "object", "response", "created_at", 1,
                "model", "fixture", "status", "completed", "output", output));
        if (!missingUsage) body.put("usage", Map.of("input_tokens", 3, "output_tokens", 2, "total_tokens", 5));
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(JSON.writeValueAsString(body));
    }
    static JsonNode usage(ConsumerHarness h) throws Exception {
        JsonNode usage = h.trace().stream().filter(r -> r.path("recordType").asText().equals("TRACE_COMPLETED")).findFirst().orElseThrow()
                .path("metadata").path("sessionUsageSnapshot");
        assertThat(usage.has("totalUnits")).isTrue(); return usage;
    }
    @Test void mixedMissionAtExactNativeAttemptQuotaPreservesProviderUsageWithoutCountingIt() throws Exception {
        for (boolean unavailable : List.of(false, true)) {
            try (var h = new ConsumerHarness("loomspan.session.quotas.max-provider-attempts=2")) {
                h.publish(List.of(transformedLeaf("decision", JSON.writeValueAsString(nativeRequest("text", "noul"))),
                        new SkillDocument("parent", "name: parent\ndescription: Native parent\nmodel: local\nallowed_skills: [{name: decision}]\n")), h.settings());
                h.planner.enqueue(nativeResponse(List.of(functionCall()), unavailable));
                h.planner.enqueue(nativeResponse(List.of(textItem("DONE")), false));
                h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"));
                String packResult = "{\"answers\":{},\"usage\":{\"unknown_units\":999999999},\"source\":\"雪\"}";
                h.response(packResult);
                assertThat(h.skills.invoke("parent", Map.of(), h.views::add)).isEqualTo("DONE");
                assertThat(h.planner.getRequestCount()).isEqualTo(2); assertThat(h.provider.getRequestCount()).isEqualTo(2);
                JsonNode usage = usage(h);
                assertThat(usage.path("providerAttempts").asInt()).isEqualTo(2); assertThat(usage.path("modelCalls").asInt()).isEqualTo(2);
                assertThat(usage.path("completionUnits").asInt()).isEqualTo(unavailable ? 2 : 4);
                assertThat(usage.path("exactModelResponses").asInt()).isEqualTo(unavailable ? 1 : 2);
                assertThat(usage.path("heuristicModelResponses").asInt()).isEqualTo(unavailable ? 1 : 0);
                if (unavailable) assertThat(usage.path("promptUnits").asInt()).isGreaterThan(3);
                else assertThat(usage.path("totalUnits").asInt()).isEqualTo(10);
                assertThat(h.trace().toString()).contains("unknown_units", "999999999", "雪");
                h.planner.takeRequest();
                assertThat(h.planner.takeRequest().getBody().readUtf8()).contains("unknown_units", "999999999");
            }
        }
    }
    @Test void executorOnlyMissingProviderUsageAndDecodeFailureKeepFrameworkTotalsZero() throws Exception {
        try (var h = new ConsumerHarness("loomspan.session.quotas.max-provider-attempts=1")) {
            h.publishNative();
            h.response("{\"answers\":{}}"); assertThat(JSON.readTree(h.invoke(nativeRequest("text", "noul"))).has("usage")).isFalse();
            assertThat(usage(h).path("providerAttempts").asInt()).isZero(); assertThat(usage(h).path("totalUnits").asInt()).isZero();
            var response = h.trace().stream().filter(r -> r.path("recordType").asText().equals("EXECUTOR_DIAGNOSTIC_RECORDED")
                    && r.path("data").path("message").asText().equals("TypeSafe response")).findFirst().orElseThrow();
            assertThat(response.at("/data/data/response").has("usage")).isFalse();
            h.response("not JSON 雪"); assertThatThrownBy(() -> h.invoke(nativeRequest("text", "noul"))).hasStackTraceContaining("StreamReadException");
            assertThat(h.provider.getRequestCount()).isEqualTo(2);
            assertThat(usage(h).path("providerAttempts").asInt()).isZero(); assertThat(usage(h).path("totalUnits").asInt()).isZero();
            assertThat(h.trace()).noneMatch(r -> r.path("recordType").asText().equals("MODEL_REQUEST_SENT"));
            assertThat(h.trace().toString()).doesNotContain("unknown_units");
        }
    }
}
