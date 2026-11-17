package ai.loomspan.decisions.internal.typesafe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ai.loomspan.decisions.internal.typesafe.TransportTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;

class TypeSafeTransportDiagnosticsTest {
    @Test void diagnosticRecordingAndPreparationFaultsCannotChangeSuccessOrHttpFailure() throws Exception {
        try(var server=new MockWebServer();var transport=new TypeSafeTransport(FAST)) {
            var invocation=invocation(server); var diagnostics=invocation.diagnostics();
            doThrow(new IllegalArgumentException("capture failed")).when(diagnostics).info(anyString(),any());
            doThrow(new IllegalArgumentException("record failed")).when(diagnostics).warn(anyString(),any());
            server.enqueue(new MockResponse().setResponseCode(429).setBody("<html>")); server.enqueue(new MockResponse().setBody("{\"usage\":{\"tokens\":42}}"));
            assertThat((Map<String,Object>)transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation)).containsKey("usage");
            server.enqueue(new MockResponse().setResponseCode(401).setBody("original"));
            assertThatThrownBy(() -> transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation))
                    .isInstanceOf(TypeSafeHttpFailure.class).hasMessage("TypeSafe returned HTTP 401");
            // Obtaining the diagnostic target is diagnostic-only preparation, also contained.
            when(invocation.diagnostics()).thenThrow(new IllegalStateException("diagnostic preparation"));
            server.enqueue(new MockResponse().setBody("null"));
            assertThat(transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation)).isNull();
        }
    }

    @Test void diagnosticsUseSupportedJsonAndRetainAuthoredEvidence() throws Exception {
        try(var server=new MockWebServer();var transport=new TypeSafeTransport(FAST)) {
            var invocation=invocation(server); var data=new ArrayList<Object>();
            var diagnostics=invocation.diagnostics();
            doAnswer(call -> {data.add(call.getArgument(1));return null;}).when(diagnostics).info(anyString(),any());
            byte[] response=fixture("openrouter-response.json");
            server.enqueue(new MockResponse().setBody(new String(response,StandardCharsets.UTF_8)));
            byte[] body=fixture("mixed-json-request.json");
            var result=transport.send(body,invocation);
            assertThat(data).hasSize(2); data.forEach(TypeSafeTransportDiagnosticsTest::supported);
            assertThat(((Map<String,Object>)data.getFirst()).get("body")).isEqualTo(new String(body,StandardCharsets.UTF_8));
            assertThat(((Map<String,Object>)data.getLast()).get("response")).isEqualTo(result);
        }
    }
    private static void supported(Object value) {
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Number)return;
        if(value instanceof Map<?,?> map) {assertThat(map.getClass().getName()).matches("java\\.util\\.(LinkedHashMap|ImmutableCollections\\$Map.*)");map.forEach((k,v)->{assertThat(k).isInstanceOf(String.class);supported(v);});return;}
        if(value instanceof List<?> list) {assertThat(list).isInstanceOf(ArrayList.class);list.forEach(TypeSafeTransportDiagnosticsTest::supported);return;}
        fail("Unsupported diagnostic value " + value.getClass());
    }
}
