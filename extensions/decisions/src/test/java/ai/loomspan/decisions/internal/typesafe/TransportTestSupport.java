package ai.loomspan.decisions.internal.typesafe;

import static org.mockito.Mockito.*;
import ai.loomspan.api.extensions.v1.ExecutorDiagnostics;
import ai.loomspan.api.extensions.v1.SkillExecutorInvocation;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import okhttp3.mockwebserver.MockWebServer;

final class TransportTestSupport {
    static final TransportPolicy FAST = new TransportPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(15), Duration.ofMillis(25), Duration.ofMillis(100));
    static SkillExecutorInvocation invocation(MockWebServer server) {
        var invocation = mock(SkillExecutorInvocation.class);
        when(invocation.skillConfig()).thenReturn(Map.of());
        when(invocation.executorConfig()).thenReturn(Map.of("api_key", "fixture-key", "base_url", server.url("/").toString()));
        when(invocation.remainingTime()).thenReturn(Optional.empty());
        when(invocation.diagnostics()).thenReturn(mock(ExecutorDiagnostics.class));
        return invocation;
    }
    static byte[] fixture(String name) throws Exception {
        try (var stream = TransportTestSupport.class.getResourceAsStream("/typesafe/" + name)) {
            return java.util.Objects.requireNonNull(stream).readAllBytes();
        }
    }
}
