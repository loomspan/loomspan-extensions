package ai.loomspan.examples.triage;

import ai.loomspan.api.extensions.v1.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ai.loomspan.examples.triage.ConsumerHarness.*;

/** Explicit fault instrumentation; business/lifecycle paths delegate to the real Framework invocation. */
class DiagnosticFaultConsumerTest {
    @Test void diagnosticTargetAndInfoWarnFaultsPreserveRealPackSuccessAndOriginalHttpFailure() throws Exception {
        for (String fault : List.of("target", "record")) {
            try (var h = new ConsumerHarness()) {
                LifecycleConsumerTest.probe(h); var reached = new AtomicInteger();
                h.probe.instrument = real -> {
                    ExecutorDiagnostics broken = mock(ExecutorDiagnostics.class, answer -> {
                        reached.incrementAndGet(); throw new IllegalStateException("injected diagnostic record failure");
                    });
                    return mock(SkillExecutorInvocation.class, answer -> {
                        if (answer.getMethod().getName().equals("diagnostics")) {
                            if (fault.equals("target")) { reached.incrementAndGet(); throw new IllegalStateException("injected diagnostic target failure"); }
                            return broken;
                        }
                        return answer.getMethod().invoke(real, answer.getArguments());
                    });
                };
                h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("malformed HTML"));
                h.response("{\"native\":true,\"usage\":{\"unknown\":123}}");
                assertThat(h.invoke(Map.of())).isEqualTo("{\"native\":true,\"usage\":{\"unknown\":123}}");
                assertThat(reached).hasValueGreaterThanOrEqualTo(5); assertThat(h.provider.getRequestCount()).isEqualTo(2);
                h.provider.enqueue(new MockResponse().setResponseCode(401).setBody("denied"));
                assertThatThrownBy(() -> h.invoke(Map.of())).hasStackTraceContaining("HTTP 401")
                        .hasStackTraceContaining("TypeSafeHttpFailure");
                assertThat(h.provider.getRequestCount()).isEqualTo(3);
            }
        }
    }
}
