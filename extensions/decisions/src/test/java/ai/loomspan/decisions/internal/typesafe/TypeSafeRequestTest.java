package ai.loomspan.decisions.internal.typesafe;

import static org.assertj.core.api.Assertions.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.*;
import tools.jackson.databind.json.JsonMapper;

class TypeSafeRequestTest {
    static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static Map<String, Object> request() {
        return new LinkedHashMap<>(Map.of("model", "jev-latest", "state", Map.of("original", true), "questions", Map.of(
                "choice", Map.of("type", "choice", "criteria", Map.of("a", "First", "b", Map.of("when", "Second"))),
                "noul", Map.of("type", "noul", "instructions", List.of("Is this repeated?")),
                "score", Map.of("type", "score", "criteria", List.of("Low", Map.of("impact", "High"))))));
    }
    static tools.jackson.databind.JsonNode build(Map<String, Object> config, Map<String, Object> input) {
        return JSON.readTree(TypeSafeRequest.prepare(config, Map.of(), input));
    }
    @Test void nativeMixedShapeAndDetachedNumbers() {
        var request = request();
        var value = new BigDecimal("0.12345678901234567890123456789");
        var input = Map.<String,Object>of("decimal", value, "integer", new BigInteger("123456789012345678901234567890"));
        var body = build(Map.of("request", request, "bindings", Map.of("/state", "")), input);
        assertThat(body.at("/state/decimal").asText()).isEqualTo(value.toPlainString());
        assertThat(body.at("/state/integer").asText()).isEqualTo(input.get("integer").toString());
        assertThat(body.at("/questions/score/criteria/1/impact").asText()).isEqualTo("High");
        assertThat(request.get("state")).isEqualTo(Map.of("original", true));
    }
    @Test void pointersPreserveNullOmitMissingAndDecodeEscapes() {
        var request = request(); request.put("state", new LinkedHashMap<>(Map.of("remove", "old")));
        var input = new LinkedHashMap<String,Object>(); input.put("a/b~", null);
        var body = build(Map.of("request", request, "bindings", Map.of("/state/value", "/a~1b~0", "/state/remove", "/missing")), input);
        assertThat(body.at("/state/value").isNull()).isTrue();
        assertThat(body.at("/state").has("remove")).isFalse();
        assertThat(build(Map.of("request", request(), "bindings", Map.of("/questions/score/criteria/0", "/new")), Map.of("new", "Changed"))
                .at("/questions/score/criteria/0").asText()).isEqualTo("Changed");
    }
    @Test void invalidPointersAndOverlapFailWithPaths() {
        for (var bindings : List.of(Map.of("", ""), Map.of("state", ""), Map.of("/~2", ""), Map.of("/missing/x", ""),
                Map.of("/model/x", ""), Map.of("/questions/score/criteria/99", ""), Map.of("/questions/score/criteria/0", "/absent"),
                Map.of("/state", "", "/state/value", ""))) {
            assertThatThrownBy(() -> build(Map.of("request", request(), "bindings", bindings), Map.of())).hasMessageContaining("skillConfig.bindings");
        }
        assertThatThrownBy(() -> build(Map.of("request", request(), "bindings", Map.of("/state", "/absent")), Map.of()))
                .hasMessageContaining("skillConfig.request.state");
    }
    @Test void localResourceReadOnceAndRemoteResourceNeverOpened() throws Exception {
        var count = new AtomicInteger();
        var resource = new ByteArrayResource("local text".getBytes()) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException { count.incrementAndGet(); return super.getInputStream(); }
        };
        var body = build(Map.of("request", request(), "bindings", Map.of("/state", "/content")), Map.of("content", resource));
        assertThat(body.get("state").asText()).isEqualTo("local text"); assertThat(count).hasValue(1);
        var remote = new UrlResource("https://example.invalid/never-fetch");
        assertThatThrownBy(() -> build(Map.of("request", request(), "bindings", Map.of("/state", "/content")), Map.of("content", remote)))
                .hasMessageContaining("unsupported Resource");
        assertThat(build(Map.of("request", request(), "bindings", Map.of("/state", "/url")), Map.of("url", "https://example.invalid/data"))
                .get("state").asText()).isEqualTo("https://example.invalid/data");
        var file = java.nio.file.Files.createTempFile("typesafe", ".txt");
        try {
            java.nio.file.Files.writeString(file, "file text");
            for (Resource local : List.of(new FileSystemResource(file), new PathResource(file), new ClassPathResource("typesafe/mixed-text-request.json")))
                assertThat(build(Map.of("request", request(), "bindings", Map.of("/state", "/content")), Map.of("content", local)).get("state").isString()).isTrue();
        } finally { java.nio.file.Files.delete(file); }
    }
    @Test void validationRejectsUnsupportedShapesNumbersAndCycles() {
        for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, new Object())) {
            var request = request(); request.put("state", Map.of("value", invalid));
            assertThatThrownBy(() -> build(Map.of("request", request), Map.of())).hasMessageContaining("skillConfig.request.state.value");
        }
        var cycle = new ArrayList<>(); cycle.add(cycle);
        var cyclicRequest = request(); cyclicRequest.put("state", cycle);
        assertThatThrownBy(() -> build(Map.of("request", cyclicRequest), Map.of())).hasMessageContaining("cyclic");
        for (String type : List.of("box", "image", "unknown")) {
            var request = request(); request.put("questions", Map.of("q", Map.of("type", type)));
            var invalid = request;
            assertThatThrownBy(() -> build(Map.of("request", invalid), Map.of())).hasMessageContaining("questions.q.type");
        }
        var image = request(); image.put("assets", Map.of("photo", "data"));
        assertThatThrownBy(() -> build(Map.of("request", image), Map.of())).hasMessageContaining("request.assets");
        var noModel = request(); noModel.remove("model");
        assertThatThrownBy(() -> build(Map.of("request", noModel), Map.of())).hasMessageContaining("request.model");
        assertThatThrownBy(() -> build(Map.of("request", request(), "extra", true), Map.of())).hasMessageContaining("skillConfig.extra");
    }
}
