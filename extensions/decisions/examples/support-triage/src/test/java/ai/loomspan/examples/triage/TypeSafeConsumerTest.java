package ai.loomspan.examples.triage;

import ai.loomspan.api.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static ai.loomspan.examples.triage.ConsumerHarness.*;

class TypeSafeConsumerTest {
    @Test void ordinaryDependencyDiscoveryIsReferenceOnlyAndPackaged() throws Exception {
        // Assert the runtime dependency really is a JAR, not reactor target/classes.
        var resource = getClass().getClassLoader().getResource("META-INF/maven/ai.loomspan/loomspan-decisions/pom.properties");
        assertThat(resource).isNotNull(); assertThat(resource.getProtocol()).isEqualTo("jar");
        try (var h = new ConsumerHarness()) {
            assertThat(h.reloader.snapshot("default").skills()).isEmpty();
            assertThatThrownBy(() -> h.skills.invoke("typesafe", Map.of())).isInstanceOf(SkillException.class);
            h.publishNative(); h.response("null");
            assertThat(h.invoke(nativeRequest("text", "noul"))).isEqualTo("null");
            assertThat(h.reloader.snapshot("default").skill("default-decision").orElseThrow().kind()).isEqualTo(SkillKind.JAVA);
            assertThat(h.views).hasSize(1);
        }
    }
    @Test void nativePrimitivesAndMixedFormsPreserveInputThroughInvocation() throws Exception {
        try (var h = new ConsumerHarness()) {
            h.publishNative();
            for (String type : List.of("choice", "noul", "score")) {
                h.response("{}");
                h.invoke(nativeRequest("native text", type));
                assertThat(JSON.readTree(h.take().getBody().readUtf8()).at("/questions/identity~1kept/type").asText()).isEqualTo(type);
            }
            var state = new LinkedHashMap<String,Object>();
            state.put("a/b~", null);
            state.put("decimal", new BigDecimal("0.12345678901234567890123456789"));
            state.put("integer", new BigInteger("123456789012345678901234567890"));
            state.put("items", List.of("雪", Map.of("image", "ordinary state key")));
            var request = nativeRequest(state, "noul");
            request.put("questions", Map.of("q", Map.of("type", "noul", "instructions", Map.of("task", "Judge"),
                    "criteria", Map.of("true", List.of("Yes"), "false", Map.of("when", "No")))));
            h.response("{}");
            h.invoke(request);
            var wire = JSON.readTree(h.take().getBody().readUtf8());
            assertThat(wire).isEqualTo(JSON.readTree(JSON.writeValueAsString(request)));
            assertThat(wire.at("/state/a~1b~0").isNull()).isTrue();
        }
    }

    static Map<String,Object> expectedTriageRequest(Map<String,Object> ticket) {
        return Map.of("model", "jev-latest", "state", ticket, "questions", Map.of(
                "team", Map.of("type", "choice", "instructions", "Select the team responsible for the ticket.", "criteria", Map.of(
                        "returns", "Damaged or unwanted goods", "billing", "Payments or invoices", "other", "Neither returns nor billing")),
                "repeat", Map.of("type", "noul", "instructions", "Has the customer contacted support more than once?"),
                "impact", Map.of("type", "score", "instructions", "Rate the practical impact on the customer.", "criteria",
                        List.of("Minor inconvenience; purchase remains usable", "Purchase partly usable", "Purchase cannot be used"))));
    }

    @Test void transformedRecipeAndDirectNativeInputSendEquivalentRequestsAndReturnCompleteResults() throws Exception {
        try (var h = new ConsumerHarness()) {
            h.publish(List.of(new SkillDocument("triage.yaml", CompositionConsumerTest.resource("triage")), nativeLeaf("decision")), h.settings());
            var ticket = Map.<String,Object>of("token", "source fidelity", "amount", new BigDecimal("0.12345678901234567890123456789"));
            var caller = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> h.skills.invoke("triage", Map.of("ticket", ticket)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(h.provider.getRequestCount()).isZero();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(caller);
            var expected = expectedTriageRequest(ticket);
            var response = (tools.jackson.databind.node.ObjectNode) JSON.readTree(TriageApplication.ILLUSTRATIVE);
            response.put("unknown", "retained");
            h.response(JSON.writeValueAsString(response));
            h.response(JSON.writeValueAsString(response));
            String transformedResult = h.skills.invoke("triage", Map.of("ticket", ticket), h.views::add);
            String directResult = h.invoke(expected);
            assertThat(JSON.readTree(transformedResult)).isEqualTo(response);
            assertThat(JSON.readTree(directResult)).isEqualTo(response);
            assertThat(TriageApplication.action(response)).isEqualTo("route to returns; priority review");
            assertThat(TriageApplication.action(JSON.readTree("{\"refusal\":\"cannot answer\"}"))).contains("refusal");
            var transformed = h.take();
            var direct = h.take();
            for (var request : List.of(transformed, direct)) {
                assertThat(request.getPath()).isEqualTo("/prefix/v1/systemone");
                assertThat(request.getHeader("Authorization")).isEqualTo("Bearer namespace-test-key");
            }
            var transformedBody = JSON.readTree(transformed.getBody().readUtf8());
            assertThat(transformedBody).isEqualTo(JSON.readTree(JSON.writeValueAsString(expected)));
            assertThat(JSON.readTree(direct.getBody().readUtf8())).isEqualTo(transformedBody);
            assertThat(h.provider.getRequestCount()).isEqualTo(2);
        }
    }
    @Test void completeNativeResultsAndSuccessfulDecodeFailures() throws Exception {
        try (var h = new ConsumerHarness()) {
            h.publishNative();
            for (String body : List.of(TriageApplication.ILLUSTRATIVE, "null", "\"quoted \\\"text\\\"\\n\"", "true", "123456789012345678901234567890", "[1,null,\"x\"]",
                    "{\"unknown\":{\"refusal\":\"native\",\"value\":0.12345678901234567890123456789},\"answers\":{\"q\":{\"confidence\":99}}}")) {
                h.response(body); assertThat(JSON.readTree(h.invoke(nativeRequest("text", "noul")))).isEqualTo(JSON.readTree(body)); h.take();
            }
            for (String invalid : List.of("", "   ", "<html>", "{} trailing")) {
                int before = h.provider.getRequestCount(); h.response(invalid);
                assertThatThrownBy(() -> h.invoke(nativeRequest("text", "noul"))).isInstanceOf(SkillException.class);
                assertThat(h.provider.getRequestCount()).isEqualTo(before + 1); h.take();
            }
            String large = "{\"source\":\"" + "fidelity".repeat(20000) + "\"}";
            h.response(large); assertThat(JSON.readTree(h.invoke(nativeRequest("text", "noul")))).isEqualTo(JSON.readTree(large)); h.take();
            assertThat(h.trace().toString()).contains("EXECUTOR_DIAGNOSTIC_SYSTEM_NOTICE_RECORDED", "UNAVAILABLE");
        }
    }
    @Test void invalidDirectAndTransformedNativeInputFailsBeforeSend() throws Exception {
        try (var h = new ConsumerHarness()) {
            var invalidModel = nativeRequest("text", "noul");
            invalidModel.put("model", " ");
            h.publish(List.of(nativeLeaf("decision"), transformedLeaf("invalid", JSON.writeValueAsString(invalidModel))), h.settings());
            assertThatThrownBy(() -> h.invoke(invalidModel)).isInstanceOf(SkillException.class).hasStackTraceContaining("input.model:");
            assertThatThrownBy(() -> h.skills.invoke("invalid", Map.of())).isInstanceOf(SkillException.class).hasStackTraceContaining("input.model:");

            for (String type : List.of("box", "image", "other")) {
                assertThatThrownBy(() -> h.invoke(nativeRequest("text", type)))
                        .isInstanceOf(SkillException.class).hasStackTraceContaining("input.questions.identity/kept.type:");
            }
            var assets = nativeRequest("text", "noul");
            assets.put("assets", Map.of("photo", Map.of("type", "image")));
            assertThatThrownBy(() -> h.invoke(assets)).isInstanceOf(SkillException.class).hasStackTraceContaining("input.assets:");
            assertThat(h.provider.getRequestCount()).isZero();
        }
    }

    @Test void retiredLeafKeysAndInvalidNamespaceSettingsFailBeforeEitherEndpointReceivesARequest() throws Exception {
        try (var h = new ConsumerHarness(); var alternate = new MockWebServer()) {
            alternate.start();
            var validRequest = nativeRequest("text", "noul");
            var alternateValues = Map.of("api_key", "alternate-key", "base_url", alternate.url("/alternate").toString());
            var legacy = new LinkedHashMap<String,Object>(alternateValues);
            legacy.put("request", validRequest);
            legacy.put("bindings", Map.of("/state", "/ticket"));
            legacy.put("extra", true);
            for (var entry : legacy.entrySet()) {
                assertRejectedLeafConfig(h, validRequest, entry.getKey(), entry.getValue());
            }
            assertRejectedLeafConfig(h, validRequest, "api_key", null);
            assertRejectedLeafConfig(h, validRequest, "bindings", Map.of());
            assertRejectedLeafConfig(h, validRequest, "base_url", "");

            for (String setting : List.of("api_key", "base_url")) {
                for (Object value : Arrays.asList(null, "", 7, "   ")) {
                    var settings = new LinkedHashMap<>(h.settings());
                    settings.put(setting, value);
                    assertRejectedSettings(h, validRequest, settings, setting);
                }
            }
            assertRejectedSettings(h, validRequest, Map.of("base_url", h.settings().get("base_url")), "api_key");
            assertRejectedSettings(h, validRequest, Map.of("api_key", "valid", "base_url", "not a URL"), "base_url");
            assertRejectedSettings(h, validRequest, Map.of("api_key", "valid", "extra", true), "extra");
            assertThat(h.provider.getRequestCount()).isZero();
            assertThat(alternate.getRequestCount()).isZero();
        }
    }

    private static void assertRejectedLeafConfig(ConsumerHarness h, Map<String,Object> request, String key, Object value) {
        var leaf = nativeLeaf("decision");
        var config = Collections.singletonMap(key, value);
        h.publish(List.of(new SkillDocument(leaf.sourceName(), leaf.yaml() + "config: " + JSON.writeValueAsString(config) + "\n")), h.settings());
        assertThatThrownBy(() -> h.invoke(request)).isInstanceOf(SkillException.class).hasStackTraceContaining("skillConfig." + key + ":");
    }

    private static void assertRejectedSettings(ConsumerHarness h, Map<String,Object> request, Map<String,Object> settings, String key) {
        h.publish(List.of(nativeLeaf("decision")), settings);
        assertThatThrownBy(() -> h.invoke(request)).isInstanceOf(SkillException.class).hasStackTraceContaining("executorConfig." + key);
    }
    @Test void retriesBodiesSettingsRedirectAndCanonicalEvidence() throws Exception {
        try (var h = new ConsumerHarness(); var second = new MockWebServer()) {
            second.start();
            h.publishNative();
            var input = nativeRequest(Map.of("token", "keep source", "password", "verbatim evidence"), "noul");
            h.provider.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("<html>busy</html>"));
            h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{broken")); h.response(TriageApplication.ILLUSTRATIVE);
            assertThat(JSON.readTree(h.invoke(input)).has("usage")).isTrue();
            var first = h.take(); assertThat(first.getPath()).isEqualTo("/prefix/v1/systemone");
            assertThat(first.getHeader("Authorization")).isEqualTo("Bearer namespace-test-key");
            String body = first.getBody().readUtf8(); assertThat(h.take().getBody().readUtf8()).isEqualTo(body); assertThat(h.take().getBody().readUtf8()).isEqualTo(body);
            var records = h.trace(); var diagnostics = records.stream().filter(r -> r.path("recordType").asText().equals("EXECUTOR_DIAGNOSTIC_RECORDED")).toList();
            assertThat(diagnostics).isNotEmpty(); assertThat(records.toString()).contains("TypeSafe request", "TypeSafe response", "TypeSafe HTTP failure", "TypeSafe retry scheduled", "keep source", "verbatim evidence");
            assertThat(diagnostics).allSatisfy(record -> {
                assertThat(record.path("data").path("messageState").asText()).isEqualTo("COMPLETE");
                assertThat(record.path("data").path("dataState").asText()).isEqualTo("COMPLETE");
                assertThat(record.path("data").path("severity").asText()).isIn("INFO", "WARN");
            });
            assertThat(diagnostics.stream().map(r -> r.path("sequence").asLong())).isSorted().doesNotHaveDuplicates();
            assertThat(diagnostics.stream().map(r -> r.path("frameId").asText()).distinct()).hasSize(1);
            for (int i = 0; i < 3; i++) h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("HTML busy"));
            int before = h.provider.getRequestCount(); assertThatThrownBy(() -> h.invoke(input)).isInstanceOf(SkillException.class);
            assertThat(h.provider.getRequestCount()).isEqualTo(before + 3); h.take(); h.take(); h.take();
            h.provider.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", second.url("/stolen")).setBody("redirect"));
            assertThatThrownBy(() -> h.invoke(input)).isInstanceOf(SkillException.class); h.take(); assertThat(second.getRequestCount()).isZero();
        }
    }
    @Test void consumerImportsOnlySupportedApplicationApi() throws Exception {
        for (String root : List.of("src/main/java", "src/test/java")) try (var files = Files.walk(Path.of(root))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String line : source.lines().filter(s -> s.startsWith("import ")).toList())
                    assertThat(line).doesNotContain("ai.loomspan.internal", "ai.loomspan.autoconfigure", "ai.loomspan.decisions.");
            }
        }
    }
}
