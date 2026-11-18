package ai.loomspan.examples.triage;

import ai.loomspan.api.SkillDocument;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static ai.loomspan.examples.triage.ConsumerHarness.*;

@EnabledIfSystemProperty(named = "loomspan.console.enabled", matches = "true")
class ConsoleConsumerTest {
    static final String DIAGNOSTIC = "EXECUTOR_DIAGNOSTIC_RECORDED", NOTICE = "EXECUTOR_DIAGNOSTIC_SYSTEM_NOTICE_RECORDED";
    @Test void actualConsoleInspectsUnmodifiedPackTracesThroughExistingBrowserAndMcpTools() throws Exception {
        try (var console = new ConsoleProcess(); var h = new ConsumerHarness("loomspan.session.quotas.max-provider-attempts=2")) {
            JsonNode tools = console.rpc("tools/list", Map.of()).path("tools"); assertThat(tools.size()).isEqualTo(13);
            for (JsonNode tool : tools) {
                if (tool.path("name").asText().equals("LOOMSPAN_query_trace_records")) assertThat(tool.toString()).contains(DIAGNOSTIC, NOTICE);
                if (List.of("LOOMSPAN_get_trace", "LOOMSPAN_query_trace_frames", "LOOMSPAN_get_execution").contains(tool.path("name").asText()))
                    assertThat(tool.path("description").asText()).contains("Framework-accounted usage");
            }
            LifecycleConsumerTest.probe(h);
            h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("<html>retry 雪"));
            String complete = "{\"answers\":{},\"usage\":{\"unknown\":999999},\"token\":\"verbatim 雪\"}";
            h.response(complete); assertThat(h.invoke(Map.of())).isEqualTo(complete);
            inspect(console, h, 0, "SUCCEEDED"); // authored ERROR from the test probe does not synthesize a failure
            h.probe.oversizedMessage = true;
            String large = "{\"complete\":\"" + "雪".repeat(20000) + "\"}";
            h.response(large); assertThat(h.invoke(Map.of())).isEqualTo(large);
            inspect(console, h, 0, "SUCCEEDED");
            assertThat(h.trace().toString()).contains(NOTICE, "UNAVAILABLE");
            assertThat(h.trace()).anyMatch(record -> record.at("/data/messageState").asText().equals("TRUNCATED"));
            h.provider.enqueue(new MockResponse().setResponseCode(401).setBody("denied"));
            assertThatThrownBy(() -> h.invoke(Map.of())).hasStackTraceContaining("HTTP 401");
            inspect(console, h, 0, "FAILED");
            // One standard native parent, two Framework calls, pack retry+success: actual mixed-mission projection.
            h.publish(List.of(transformedLeaf("decision", JSON.writeValueAsString(nativeRequest("mixed", "noul"))),
                    new SkillDocument("parent", "name: parent\ndescription: Native parent\nmodel: local\nallowed_skills: [{name: decision}]\n")), h.settings());
            h.planner.enqueue(AccountingConsumerTest.nativeResponse(List.of(AccountingConsumerTest.functionCall()), false));
            h.planner.enqueue(AccountingConsumerTest.nativeResponse(List.of(AccountingConsumerTest.textItem("DONE")), false));
            h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy")); h.response(complete);
            assertThat(h.skills.invoke("parent", Map.of(), h.views::add)).isEqualTo("DONE"); inspect(console, h, 10, "SUCCEEDED");
        }
    }
    static void inspect(ConsoleProcess console, ConsumerHarness h, long expectedUsage, String outcome) throws Exception {
        var records = h.trace(); var imported = console.importTrace(h.traceFile(h.views.getLast().sessionId()));
        String trace = imported.path("traceId").asText(); assertThat(trace).isEqualTo(records.getFirst().path("traceId").asText());
        var summary = console.tool("LOOMSPAN_get_trace", Map.of("traceId", trace)).path("summary");
        assertThat(summary.path("outcome").asText()).isEqualTo(outcome);
        assertThat(summary.at("/terminalUsage/totalUnits").asLong()).isEqualTo(expectedUsage);
        assertThat(summary.path("attemptCount").asInt()).isEqualTo(expectedUsage == 0 ? 0 : 2);
        if (outcome.equals("SUCCEEDED")) assertThat(summary.path("failureCount").asInt()).isZero();
        var browserSummary = console.browser("/traces/analysis/summary", Map.of("traceId", trace, "source", "IMPORTED"));
        assertThat(browserSummary.path("outcome")).isEqualTo(summary.path("outcome"));
        assertThat(browserSummary.path("terminalUsage")).isEqualTo(summary.path("terminalUsage"));
        String frame = records.stream().filter(r -> r.path("recordType").asText().equals(DIAGNOSTIC)).findFirst().orElseThrow().path("frameId").asText();
        var selections = List.of(List.<String>of(), List.of(DIAGNOSTIC), List.of(NOTICE), List.of(DIAGNOSTIC, NOTICE), List.of("FRAME_OPENED", DIAGNOSTIC));
        for (String representation : List.of("LOGICAL", "PHYSICAL")) for (var types : selections) for (String selectedFrame : List.of("", frame, "unrelated-frame")) {
            var expected = records.stream().filter(r -> !representation.equals("LOGICAL") || !r.path("recordType").asText().equals("PAYLOAD_CHUNK_APPENDED"))
                    .filter(r -> types.isEmpty() || types.contains(r.path("recordType").asText()))
                    .filter(r -> selectedFrame.isEmpty() || r.path("frameId").asText().equals(selectedFrame)).map(r -> r.path("sequence").asLong()).toList();
            var actual = new ArrayList<Long>(); String continuation = ""; int pages = 0;
            do {
                assertThat(++pages).isLessThan(500);
                var args = new LinkedHashMap<String,Object>(Map.of("traceId", trace, "pageSize", 2, "representation", representation, "inlineContent", false));
                var filter = new LinkedHashMap<String,Object>(); if (!types.isEmpty()) filter.put("types", types); if (!selectedFrame.isEmpty()) filter.put("frameId", selectedFrame); args.put("filter", filter);
                if (!continuation.isEmpty()) args.put("continuation", continuation);
                JsonNode page = console.tool("LOOMSPAN_query_trace_records", args);
                var browserArgs = new LinkedHashMap<>(args); browserArgs.put("source", "IMPORTED"); browserArgs.remove("continuation");
                if (!continuation.isEmpty()) browserArgs.put("cursor", continuation);
                JsonNode browserPage = console.browser("/traces/analysis/records", browserArgs);
                assertThat(browserPage.path("items").size()).isEqualTo(page.path("items").size());
                int index = 0;
                for (var record : page.path("items")) {
                    long sequence = record.path("sequence").asLong(); actual.add(sequence);
                    JsonNode canonical = records.stream().filter(r -> r.path("sequence").asLong() == sequence).findFirst().orElseThrow();
                    assertThat(record.path("type").asText()).isEqualTo(canonical.path("recordType").asText());
                    assertThat(record.path("frameId").asText()).isEqualTo(canonical.path("frameId").asText());
                    assertThat(browserPage.path("items").get(index++).path("sequence").asLong()).isEqualTo(sequence);
                    assertThat(record.at("/content/inlineContent").asText()).isEmpty();
                }
                if (!page.path("hasMore").asBoolean()) break;
                String next = page.path("continuation").asText(); assertThat(next).isNotBlank().isNotEqualTo(continuation); continuation = next;
            } while (true);
            assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        }
        var diagnostics = console.tool("LOOMSPAN_query_trace_records", Map.of("traceId", trace, "pageSize", 64, "inlineContent", true, "filter", Map.of("types", List.of(DIAGNOSTIC, NOTICE))));
        assertThat(diagnostics.path("items").size()).isPositive();
        assertThat(diagnostics.path("hasMore").asBoolean()).isFalse();
        int aggregateInlineBytes = 0;
        for (var record : diagnostics.path("items")) {
            long sequence = record.path("sequence").asLong();
            JsonNode canonical = records.stream().filter(r -> r.path("sequence").asLong() == sequence).findFirst().orElseThrow();
            String contentRef = record.at("/content/contentRef").asText(); assertThat(contentRef).isNotBlank();
            var bytes = new ByteArrayOutputStream(); String continuation = ""; int pages = 0;
            do {
                assertThat(++pages).isLessThan(1000);
                var args = new LinkedHashMap<String,Object>(Map.of("traceId", trace, "contentRef", contentRef, "maxBytes", 257));
                if (!continuation.isEmpty()) args.put("continuation", continuation);
                JsonNode part = console.tool("LOOMSPAN_read_trace_content", args);
                var browserArgs = new LinkedHashMap<>(args); browserArgs.put("source", "IMPORTED"); browserArgs.remove("continuation");
                if (!continuation.isEmpty()) browserArgs.put("continueCursor", continuation);
                JsonNode browserPart = console.browser("/traces/analysis/content-range", browserArgs);
                assertThat(browserPart.path("content")).isEqualTo(part.path("content")); assertThat(browserPart.path("encoding")).isEqualTo(part.path("encoding"));
                byte[] chunk = part.path("encoding").asText().equals("BASE64") ? Base64.getDecoder().decode(part.path("content").asText()) : part.path("content").asText().getBytes(StandardCharsets.UTF_8);
                assertThat(chunk.length).isLessThanOrEqualTo(257); bytes.write(chunk);
                if (!part.path("hasMore").asBoolean()) break;
                String next = part.path("continuation").asText(); assertThat(next).isNotBlank().isNotEqualTo(continuation); continuation = next;
            } while (true);
            JsonNode payload = JSON.readTree(bytes.toByteArray()); assertThat(payload).isEqualTo(canonical.path("data"));
            String inline = record.at("/content/inlineContent").asText();
            int inlineBytes = inline.getBytes(StandardCharsets.UTF_8).length;
            aggregateInlineBytes += inlineBytes;
            assertThat(inlineBytes).isLessThanOrEqualTo(8192);
            if (bytes.size() > 8192) {
                assertThat(inline).isEmpty(); assertThat(record.at("/content/inlineEligibility").asBoolean()).isFalse();
                assertThat(record.at("/content/inlineOmission").asText()).isNotBlank();
            } else assertThat(JSON.readTree(inline)).isEqualTo(payload);
            if (record.path("type").asText().equals(NOTICE)) assertThat(payload.has("severity")).isFalse();
            else assertThat(payload.path("severity").asText()).isIn("INFO", "WARN", "ERROR");
            assertThat(record.path("attempts").size()).isZero(); assertThat(record.path("failures").size()).isZero();
        }
        assertThat(aggregateInlineBytes).isLessThanOrEqualTo(32768);
    }
}
