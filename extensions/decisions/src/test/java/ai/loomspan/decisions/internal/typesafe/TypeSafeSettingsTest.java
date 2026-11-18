package ai.loomspan.decisions.internal.typesafe;

import static org.assertj.core.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TypeSafeSettingsTest {
    @Test void executorSettingsPreservePrefixAndVerbatimCredentials() {
        var settings = TypeSafeSettings.capture(Map.of("api_key", " key ${LITERAL} ", "base_url", "http://localhost:82/api%20x///"));
        assertThat(settings.apiKey()).isEqualTo(" key ${LITERAL} ");
        assertThat(settings.endpoint().toString()).isEqualTo("http://localhost:82/api%20x/v1/systemone");
        assertThat(TypeSafeSettings.capture(Map.of("api_key", "x")).endpoint().toString())
                .isEqualTo("https://api.typesafe.ai/v1/systemone");
        assertThatThrownBy(() -> TypeSafeSettings.capture(Map.of())).hasMessageContaining("executorConfig.api_key");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t", "bad\r\nheader", "bad\u0000header"})
    void invalidPresentKeyFails(String value) {
        var settings = new HashMap<String,Object>();
        settings.put("api_key", value);
        assertThatThrownBy(() -> TypeSafeSettings.capture(settings))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("executorConfig.api_key");
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "http://", "ftp://localhost", "//localhost", "http://user@localhost", "http://localhost?a=b",
            "http://localhost/#x", "http://localhost:65536", "http://localhost:-2", "http://localhost:", " http://localhost",
            "http://localhost/ ", "http://invalid_host"})
    void invalidPresentUrlFails(String value) {
        var settings = new HashMap<String,Object>();
        settings.put("api_key", "valid");
        settings.put("base_url", value);
        assertThatThrownBy(() -> TypeSafeSettings.capture(settings))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("executorConfig.base_url");
    }

    @Test void nonStringsFailAtExecutorPath() {
        for (String name : new String[]{"api_key", "base_url"}) {
            var values = new HashMap<String,Object>();
            values.put("api_key", "valid");
            values.put(name, 42);
            assertThatThrownBy(() -> TypeSafeSettings.capture(values)).hasMessageContaining("executorConfig." + name);
        }
    }

    @Test void capturedValueDoesNotTrackLaterMapChanges() {
        var values = new HashMap<String,Object>();
        values.put("api_key", "old");
        values.put("base_url", "http://localhost/old");
        var old = TypeSafeSettings.capture(values);
        values.put("api_key", "new");
        values.put("base_url", "http://localhost/new");
        assertThat(old.apiKey()).isEqualTo("old");
        assertThat(old.endpoint().getPath()).isEqualTo("/old/v1/systemone");
        assertThat(TypeSafeSettings.capture(values).apiKey()).isEqualTo("new");
    }
}
