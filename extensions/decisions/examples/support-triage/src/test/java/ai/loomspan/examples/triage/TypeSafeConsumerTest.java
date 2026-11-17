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
            h.publish(Map.of("request", nativeRequest("text", "noul"))); h.response("null");
            assertThat(h.invoke(Map.of())).isEqualTo("null");
            assertThat(h.reloader.snapshot("default").skill("default-decision").orElseThrow().kind()).isEqualTo(SkillKind.JAVA);
            assertThat(h.views).hasSize(1);
        }
    }
    @Test void nativePrimitivesMixedFormsPrecisionAndBindingsThroughInvocation() throws Exception {
        try (var h = new ConsumerHarness()) {
            for (String type : List.of("choice", "noul", "score")) {
                h.publish(Map.of("request", nativeRequest("native text", type))); h.response("{}"); h.invoke(Map.of());
                assertThat(JSON.readTree(h.take().getBody().readUtf8()).at("/questions/identity~1kept/type").asText()).isEqualTo(type);
            }
            String yaml = new String(getClass().getResourceAsStream("/triage.yaml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            h.publish(List.of(new SkillDocument("triage.yaml", yaml)), h.settings()); h.response(TriageApplication.ILLUSTRATIVE);
            var caller = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> h.skills.invoke("triage", Map.of("ticket", Map.of())))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(h.provider.getRequestCount()).isEqualTo(3);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(caller);
            String result = h.skills.invoke("triage", Map.of("ticket", Map.of("token", "source fidelity", "amount", new BigDecimal("0.12345678901234567890123456789"))), h.views::add);
            var wire = JSON.readTree(h.take().getBody().readUtf8());
            assertThat(wire.at("/state/amount").asText()).isEqualTo("0.12345678901234567890123456789");
            assertThat(wire.get("questions").size()).isEqualTo(3);
            assertThat(wire.at("/questions/impact/criteria/0").asText()).startsWith("Minor");
            assertThat(TriageApplication.action(JSON.readTree(result))).isEqualTo("route to returns; priority review");
            assertThat(TriageApplication.action(JSON.readTree("{\"refusal\":\"cannot answer\"}"))).contains("refusal");
            var request = nativeRequest(Map.of("remove", "old"), "noul");
            h.publish(Map.of("request", request, "bindings", Map.of("/state/nullable", "/value", "/state/remove", "/absent")));
            var input = new LinkedHashMap<String,Object>(); input.put("value", null);
            h.response("{}"); h.invoke(input); wire = JSON.readTree(h.take().getBody().readUtf8());
            assertThat(wire.at("/state/nullable").isNull()).isTrue(); assertThat(wire.get("state").has("remove")).isFalse();
            // Native structured instructions and criteria remain authored JSON, without key heuristics.
            request = nativeRequest(List.of("array", Map.of("image", "ordinary state key")), "noul");
            request.put("questions", Map.of("q", Map.of("type", "noul", "instructions", Map.of("task", "Judge"), "criteria", Map.of("true", List.of("Yes"), "false", Map.of("when", "No")))));
            h.publish(Map.of("request", request)); h.response("{}"); h.invoke(Map.of());
            assertThat(JSON.readTree(h.take().getBody().readUtf8()).get("state").isArray()).isTrue();
            // Ordinary YAML executor config conversion is accepted, distinct from precise input numbers.
            yaml = leaf("decision", Map.of("request", nativeRequest(Map.of("decimal", 1), "noul"))).yaml().replace("\"decimal\":1", "\"decimal\":0.12345678901234567890123456789");
            h.publish(List.of(new SkillDocument("decimal.yaml", yaml)), h.settings()); h.response("{}"); h.invoke(Map.of());
            assertThat(JSON.readTree(h.take().getBody().readUtf8()).at("/state/decimal").asText()).isEqualTo("0.12345678901234568");
        }
    }
    @Test void completeNativeResultsAndSuccessfulDecodeFailures() throws Exception {
        try (var h = new ConsumerHarness()) {
            h.publish(Map.of("request", nativeRequest("text", "noul")));
            for (String body : List.of(TriageApplication.ILLUSTRATIVE, "null", "\"quoted \\\"text\\\"\\n\"", "true", "123456789012345678901234567890", "[1,null,\"x\"]",
                    "{\"unknown\":{\"refusal\":\"native\",\"value\":0.12345678901234567890123456789},\"answers\":{\"q\":{\"confidence\":99}}}")) {
                h.response(body); assertThat(JSON.readTree(h.invoke(Map.of()))).isEqualTo(JSON.readTree(body)); h.take();
            }
            for (String invalid : List.of("", "   ", "<html>", "{} trailing")) {
                int before = h.provider.getRequestCount(); h.response(invalid);
                assertThatThrownBy(() -> h.invoke(Map.of())).isInstanceOf(SkillException.class);
                assertThat(h.provider.getRequestCount()).isEqualTo(before + 1); h.take();
            }
            String large = "{\"source\":\"" + "fidelity".repeat(20000) + "\"}";
            h.response(large); assertThat(JSON.readTree(h.invoke(Map.of()))).isEqualTo(JSON.readTree(large)); h.take();
            assertThat(h.trace().toString()).contains("EXECUTOR_DIAGNOSTIC_SYSTEM_NOTICE_RECORDED", "UNAVAILABLE");
        }
    }
    @Test void localInvalidConfigCapabilitiesAndPresentOverridesNeverSend() throws Exception {
        try (var h = new ConsumerHarness()) {
            var invalid = new ArrayList<Map<String,Object>>();
            for (String type : List.of("box", "image", "other")) invalid.add(Map.of("request", nativeRequest("text", type)));
            var assets = nativeRequest("text", "noul"); assets.put("assets", Map.of("photo", Map.of("type", "image"))); invalid.add(Map.of("request", assets));
            var model = nativeRequest("text", "noul"); model.put("model", " "); invalid.add(Map.of("request", model));
            invalid.add(Map.of("request", nativeRequest("text", "noul"), "bindings", Map.of("state", "/ticket")));
            for (String setting : List.of("api_key", "base_url")) for (Object value : Arrays.asList(null, "", 7, "   ")) {
                var config = new LinkedHashMap<String,Object>(); config.put("request", nativeRequest("text", "noul")); config.put(setting, value); invalid.add(config);
            }
            invalid.add(Map.of("request", nativeRequest("text", "noul"), "base_url", "not a URL"));
            for (var config : invalid) {
                h.publish(config);
                assertThatThrownBy(() -> h.invoke(Map.of())).isInstanceOf(SkillException.class).hasStackTraceContaining("Config");
                assertThat(h.provider.getRequestCount()).isZero();
            }
            h.publish(List.of(leaf("decision", Map.of("request", nativeRequest("text", "noul")))), Map.of());
            assertThatThrownBy(() -> h.invoke(Map.of())).hasStackTraceContaining("api_key"); assertThat(h.provider.getRequestCount()).isZero();
        }
    }
    @Test void retriesBodiesSettingsRedirectAndCanonicalEvidence() throws Exception {
        try (var h = new ConsumerHarness(); var second = new MockWebServer()) {
            second.start();
            h.publish(Map.of("request", nativeRequest(Map.of("token", "keep source", "password", "verbatim evidence"), "noul")));
            h.provider.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("<html>busy</html>"));
            h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{broken")); h.response(TriageApplication.ILLUSTRATIVE);
            assertThat(JSON.readTree(h.invoke(Map.of())).has("usage")).isTrue();
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
            int before = h.provider.getRequestCount(); assertThatThrownBy(() -> h.invoke(Map.of())).isInstanceOf(SkillException.class);
            assertThat(h.provider.getRequestCount()).isEqualTo(before + 3); h.take(); h.take(); h.take();
            h.provider.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", second.url("/stolen")).setBody("redirect"));
            assertThatThrownBy(() -> h.invoke(Map.of())).isInstanceOf(SkillException.class); h.take(); assertThat(second.getRequestCount()).isZero();
            h.publish(Map.of("request", nativeRequest("text", "noul"), "api_key", "skill-test-key", "base_url", second.url("/skill-prefix/").toString()));
            second.enqueue(new MockResponse().setBody("{}")); h.invoke(Map.of());
            var selected = second.takeRequest(); assertThat(selected.getPath()).isEqualTo("/skill-prefix/v1/systemone");
            assertThat(selected.getHeader("Authorization")).isEqualTo("Bearer skill-test-key");
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
