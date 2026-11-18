package ai.loomspan.examples.triage;

import java.util.*;
import java.util.concurrent.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static ai.loomspan.examples.triage.ConsumerHarness.*;

class PublicationConsumerTest {
    static Callable<String> invoke(ConsumerHarness h, String name) {
        return () -> { authenticate(); try { return h.skills.invoke(name, Map.of(), h.views::add); }
            finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } };
    }
    @Test void concurrentDistinctEndpointsAndPublicationKeepCapturedCredentialsAndRetryBodies() throws Exception {
        try (var h = new ConsumerHarness(); var second = new MockWebServer(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            second.start();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            h.provider.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (count.incrementAndGet() == 1) { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release");
                        return new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("<html>busy"); }
                    return new MockResponse().setBody("{\"old\":true}");
                }
            });
            h.publish(List.of(transformedLeaf("decision", JSON.writeValueAsString(nativeRequest("old", "noul")))), h.settings());
            String generation = h.reloader.snapshot("default").generationId();
            Object shared = h.context.getBean("typeSafeTransport");
            var retired = new CopyOnWriteArrayList<String>();
            try (var registration = h.reloader.onGenerationRetired(retired::add)) {
                Future<String> old = workers.submit(invoke(h, "decision"));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    var settings = Map.<String,Object>of("api_key", "new-distinct-key", "base_url", second.url("/new").toString());
                    h.publish(List.of(transformedLeaf("decision", JSON.writeValueAsString(nativeRequest("new", "noul")))), settings);
                    assertThat(h.reloader.snapshot("default").generationId()).isNotEqualTo(generation);
                    second.enqueue(new MockResponse().setBody("{\"new\":true}"));
                    assertThat(workers.submit(invoke(h, "decision")).get(5, TimeUnit.SECONDS)).isEqualTo("{\"new\":true}");
                    assertThat(retired).doesNotContain(generation);
                    assertThat(h.context.getBean("typeSafeTransport")).isSameAs(shared);
                    var selected = second.takeRequest(2, TimeUnit.SECONDS);
                    assertThat(selected.getPath()).isEqualTo("/new/v1/systemone");
                    assertThat(selected.getHeader("Authorization")).isEqualTo("Bearer new-distinct-key");
                    assertThat(JSON.readTree(selected.getBody().readUtf8()).path("state").asText()).isEqualTo("new");
                } finally { release.countDown(); }
                assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo("{\"old\":true}");
                var first = h.take(); var retry = h.take();
                for (var request : List.of(first, retry)) {
                    assertThat(request.getPath()).isEqualTo("/prefix/v1/systemone");
                    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer namespace-test-key");
                }
                String oldBody = first.getBody().readUtf8();
                assertThat(oldBody).isEqualTo(retry.getBody().readUtf8());
                assertThat(JSON.readTree(oldBody).path("state").asText()).isEqualTo("old");
                assertThat(h.provider.getRequestCount()).isEqualTo(2); assertThat(second.getRequestCount()).isOne();
                assertThat(h.views.stream().map(v -> v.sessionId()).distinct()).hasSize(2);
                for (var view : h.views) assertThat(h.trace(view.sessionId())).isNotEmpty();
            }
        }
    }
}
