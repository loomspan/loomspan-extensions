package ai.loomspan.decisions.internal.typesafe;

import static org.assertj.core.api.Assertions.*;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TypeSafeRequestTest {
    static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    static Map<String, Object> request() {
        return new LinkedHashMap<>(Map.of("model", "jev-latest", "state", Map.of("original", true), "questions", Map.of(
                "choice", Map.of("type", "choice", "criteria", Map.of("a", "First", "b", Map.of("when", "Second"))),
                "noul", Map.of("type", "noul", "instructions", List.of("Is this repeated?")),
                "score", Map.of("type", "score", "criteria", List.of("Low", Map.of("impact", "High"))))));
    }

    private static Map<String, Object> withState(Object state) {
        var input = request();
        input.put("state", state);
        return input;
    }

    private static JsonNode build(Map<String, Object> input) {
        return JSON.readTree(TypeSafeRequest.prepare(Map.of(), input));
    }

    @Test void nativeInputIsTheCompleteRequest() {
        var input = request();
        assertThat(build(input)).isEqualTo(JSON.valueToTree(input));
    }

    @Test void nativeInputPreservesNestedValuesAndProducesDetachedBytes() {
        var state = new LinkedHashMap<String, Object>();
        state.put("a/b~", null);
        state.put("nested", new ArrayList<>(List.of("café", Map.of("finite", 1.25))));
        state.put("decimal", new BigDecimal("0.12345678901234567890123456789"));
        state.put("integer", new BigInteger("123456789012345678901234567890"));
        var input = withState(state);
        var expected = JSON.readTree(JSON.writeValueAsBytes(input));

        byte[] bytes = TypeSafeRequest.prepare(Map.of(), input);
        assertThat(JSON.readTree(JSON.writeValueAsBytes(input))).isEqualTo(expected);
        state.put("nested", List.of("changed"));
        var body = JSON.readTree(bytes);
        assertThat(body).isEqualTo(expected);
        assertThat(body.at("/state/a~1b~0").isNull()).isTrue();
        assertThat(body.at("/state/decimal").asText()).isEqualTo("0.12345678901234567890123456789");
        assertThat(body.at("/state/integer").asText()).isEqualTo("123456789012345678901234567890");
    }

    @Test void localResourceReadOnceAndRemoteResourceNeverOpened() throws Exception {
        var count = new AtomicInteger();
        var resource = new ByteArrayResource("local text".getBytes(StandardCharsets.UTF_8)) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                count.incrementAndGet();
                return super.getInputStream();
            }
        };
        assertThat(build(withState(resource)).get("state").asText()).isEqualTo("local text");
        assertThat(count).hasValue(1);
        var remote = new UrlResource("https://example.invalid/never-fetch") {
            @Override public java.io.InputStream getInputStream() {
                throw new AssertionError("Unsupported Resource was opened");
            }
        };
        assertThatThrownBy(() -> build(withState(remote))).hasMessageContaining("input.state").hasMessageContaining("unsupported Resource");
        assertThat(build(withState("https://example.invalid/data")).get("state").asText()).isEqualTo("https://example.invalid/data");
        var file = Files.createTempFile("typesafe", ".txt");
        try {
            Files.writeString(file, "file text");
            for (Resource local : List.of(new FileSystemResource(file), new PathResource(file), new ClassPathResource("typesafe/mixed-text-request.json"))) {
                assertThat(build(withState(local)).get("state").isString()).isTrue();
            }
        } finally {
            Files.delete(file);
        }
    }

    @Test void validationRejectsUnsupportedShapesNumbersAndCycles() {
        for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, new Object())) {
            assertThatThrownBy(() -> build(withState(Map.of("value", invalid)))).hasMessageContaining("input.state.value");
        }
        var cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThatThrownBy(() -> build(withState(cycle))).hasMessageContaining("input.state[0]").hasMessageContaining("cyclic");

        assertRejected("model", null, "model");
        assertRejected("model", " ", "model");
        assertRejected("state", null, "state");
        assertRejected("state", 7, "state");
        assertRejected("assets", Map.of("photo", "data"), "assets");
        assertRejected("questions", Map.of(), "questions");
        assertRejected("questions", List.of(), "questions");
        assertRejected("questions", Map.of("q", "question"), "questions.q");
        for (String type : List.of("box", "image", "unknown")) {
            assertRejected("questions", Map.of("q", Map.of("type", type)), "questions.q.type");
        }
        assertRejected("questions", Map.of("q", Map.of("type", "choice", "criteria", List.of("one"))), "questions.q.criteria");
        assertRejected("questions", Map.of("q", Map.of("type", "score", "criteria", List.of(1))), "questions.q.criteria[0]");
        assertRejected("questions", Map.of("q", Map.of("type", "noul", "criteria", Map.of("other", "x"))), "questions.q.criteria.other");
    }

    private static void assertRejected(String field, Object value, String path) {
        var input = request();
        if (value == null) input.remove(field);
        else input.put(field, value);
        assertThatThrownBy(() -> build(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input." + path + ":");
    }

    @Test void serializedBodyLimitAppliesToNativeInput() {
        var input = withState("x".repeat(32 * 1024 * 1024));
        assertThatThrownBy(() -> build(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input: exceeds 32 MiB JSON body");
    }

    @Test void rejectsInvalidAttemptLimits() {
        for (Object value : Arrays.asList(null, true, "1", 1.0, 1.5, 0, -1, 4, BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE))) {
            assertThatThrownBy(() -> TypeSafeRequest.maxAttempts(Collections.singletonMap("max_attempts", value)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skillConfig.max_attempts");
        }
    }

    @Test void rejectsUnknownLeafAndExecutorKeys() {
        var oldValues = Map.of("request", request(), "bindings", Map.of("/state", "/ticket"), "api_key", "key",
                "base_url", "http://localhost", "extra", true);
        for (var entry : oldValues.entrySet()) {
            for (Object value : Arrays.asList(entry.getValue(), "", Map.of(), null)) {
                assertThatThrownBy(() -> TypeSafeRequest.maxAttempts(Collections.singletonMap(entry.getKey(), value)))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skillConfig." + entry.getKey() + ":");
            }
        }
        assertThatThrownBy(() -> TypeSafeRequest.prepare(Map.of("api_key", "valid", "extra", true), request()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("executorConfig.extra:");
    }
}
