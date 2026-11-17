package ai.loomspan.decisions.internal.typesafe;

import static org.assertj.core.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TypeSafeSettingsTest {
    @Test void presencePrecedenceDefaultsAndVerbatimCredentials() {
        var lower = Map.<String,Object>of("api_key", "executor", "base_url", "http://localhost:81/lower");
        var selected = TypeSafeSettings.capture(Map.of("api_key", " key ${LITERAL} ", "base_url", "http://localhost:82/api%20x///"), lower);
        assertThat(selected.apiKey()).isEqualTo(" key ${LITERAL} ");
        assertThat(selected.endpoint().toString()).isEqualTo("http://localhost:82/api%20x/v1/systemone");
        assertThat(TypeSafeSettings.capture(Map.of(), Map.of("api_key", "x")).endpoint().toString())
                .isEqualTo("https://api.typesafe.ai/v1/systemone");
        assertThat(TypeSafeSettings.capture(Map.of("api_key", "skill"), lower).endpoint().toString())
                .isEqualTo("http://localhost:81/lower/v1/systemone");
        assertThat(TypeSafeSettings.capture(Map.of("base_url", "http://localhost/s"), lower).apiKey()).isEqualTo("executor");
        assertThatThrownBy(() -> TypeSafeSettings.capture(Map.of(), Map.of())).hasMessageContaining("executorConfig.api_key");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t", "bad\r\nheader", "bad\u0000header"})
    void invalidPresentKeyNeverFallsBack(String value) {
        var high = new HashMap<String,Object>(); high.put("api_key", value);
        assertThatThrownBy(() -> TypeSafeSettings.capture(high, Map.of("api_key", "valid")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skillConfig.api_key");
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "http://", "ftp://localhost", "//localhost", "http://user@localhost", "http://localhost?a=b",
            "http://localhost/#x", "http://localhost:65536", "http://localhost:-2", "http://localhost:", " http://localhost", "http://localhost/ "})
    void invalidPresentUrlNeverFallsBack(String value) {
        var high = new HashMap<String,Object>(); high.put("base_url", value);
        assertThatThrownBy(() -> TypeSafeSettings.capture(high, Map.of("api_key", "valid", "base_url", "http://localhost")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skillConfig.base_url");
    }

    @Test void nonStringsAndInvalidExecutorValuesFailAtSelectedPath() {
        for (String name : new String[]{"api_key", "base_url"}) {
            var values = new HashMap<String,Object>(); values.put("api_key", "valid"); values.put(name, 42);
            assertThatThrownBy(() -> TypeSafeSettings.capture(Map.of(), values)).hasMessageContaining("executorConfig." + name);
            assertThatThrownBy(() -> TypeSafeSettings.capture(Map.of(name, 42), Map.of("api_key", "valid")))
                    .hasMessageContaining("skillConfig." + name);
        }
    }

    @Test void capturedValueDoesNotTrackLaterMapChanges() {
        var values = new HashMap<String,Object>(); values.put("api_key", "old"); values.put("base_url", "http://localhost/old");
        var old = TypeSafeSettings.capture(Map.of(), values);
        values.put("api_key", "new"); values.put("base_url", "http://localhost/new");
        assertThat(old.apiKey()).isEqualTo("old");
        assertThat(old.endpoint().getPath()).isEqualTo("/old/v1/systemone");
        assertThat(TypeSafeSettings.capture(Map.of(), values).apiKey()).isEqualTo("new");
    }
}
