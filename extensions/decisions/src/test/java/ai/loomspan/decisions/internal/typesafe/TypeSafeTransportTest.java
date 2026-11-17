package ai.loomspan.decisions.internal.typesafe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ai.loomspan.decisions.internal.typesafe.TransportTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TypeSafeTransportTest {
    @ParameterizedTest @ValueSource(strings = {"mixed-json-request.json", "mixed-text-request.json"})
    void preservesPreparedRequestsAndCompleteEnvelopes(String fixtureName) throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setBody(new String(fixture("complete-response.json"), StandardCharsets.UTF_8)));
            var invocation = invocation(server);
            when(invocation.skillConfig()).thenReturn(Map.of("api_key", " leading internal key", "base_url", server.url("/api/prefix/").toString()));
            byte[] body = fixture(fixtureName);
            var result = (Map<String,Object>) transport.send(body, invocation);
            var request = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/api/prefix/v1/systemone");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer  leading internal key");
            assertThat(request.getHeader("Content-Type")).startsWith("application/json");
            assertThat(request.getBody().readByteArray()).isEqualTo(body);
            assertThat(result).containsKeys("answers", "usage", "future_field", "model");
            assertThat(transport.pendingDeadlineTasks()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(ints = {408,429,500,502,503,504,529})
    void retriesKnownStatusesRegardlessOfMalformedErrorBody(int status) throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("<html>{broken"));
            server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
            var invocation = invocation(server);
            when(invocation.executorConfig()).thenReturn(Map.of("api_key", "old", "base_url", server.url("/old").toString()),
                    Map.of("api_key", "new", "base_url", "http://localhost:1/new"));
            byte[] body = fixture("mixed-json-request.json");
            transport.send(body, invocation);
            assertThat(server.getRequestCount()).isEqualTo(2);
            for (int i = 0; i < 2; i++) {
                var request = server.takeRequest();
                assertThat(request.getPath()).isEqualTo("/old/v1/systemone");
                assertThat(request.getHeader("Authorization")).isEqualTo("Bearer old");
                assertThat(request.getBody().readByteArray()).isEqualTo(body);
            }
            verify(invocation, times(1)).executorConfig();
        }
    }

    @Test void retryCardinalityIsExactlyThree() throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            for (int i=0;i<4;i++) server.enqueue(new MockResponse().setResponseCode(529).setBody("oops"));
            byte[] bytes = fixture("mixed-text-request.json");
            assertThatThrownBy(() -> transport.send(bytes, invocation(server))).isInstanceOf(TypeSafeHttpFailure.class);
            assertThat(server.getRequestCount()).isEqualTo(3);
            for (int i=0;i<3;i++) assertThat(server.takeRequest().getBody().readByteArray()).isEqualTo(bytes);
        }
    }

    @ParameterizedTest @ValueSource(ints = {400,401,403,404,413,422})
    void permanentStatusDoesNotRetry(int status) throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("bad"));
            server.enqueue(new MockResponse().setBody("{}"));
            assertThatThrownBy(() -> transport.send(new byte[]{123,125}, invocation(server))).hasMessageContaining("HTTP " + status);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(ints = {302,307})
    void redirectsNeverReachTarget(int status) throws Exception {
        try (var server = new MockWebServer(); var target = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            target.start();
            server.enqueue(new MockResponse().setResponseCode(status).setHeader("Location", target.url("/collect")));
            assertThatThrownBy(() -> transport.send("{}".getBytes(StandardCharsets.UTF_8), invocation(server)))
                    .hasMessageContaining("HTTP " + status);
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(target.takeRequest(150,TimeUnit.MILLISECONDS)).isNull();
            assertThat(target.getRequestCount()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "  ", "{broken", "{} {}", "null false"})
    void malformedSuccessfulJsonNeverRetries(String response) throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setBody(response)); server.enqueue(new MockResponse().setBody("{}"));
            assertThatThrownBy(() -> transport.send("{}".getBytes(StandardCharsets.UTF_8), invocation(server))).isInstanceOf(RuntimeException.class);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test void allNativeJsonShapesAndLargeResultsArePreserved() throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            String large = "x".repeat(100_000);
            String[] json = {"null", "\"quoted\"", "[true,false,null]", "123456789012345678901234567890", "0.123456789012345678901234567890", "true", "{\"large\":\""+large+"\"}"};
            Object[] expected = {null, "quoted", java.util.Arrays.asList(true,false,null), new java.math.BigInteger(json[3]), new java.math.BigDecimal(json[4]), true, Map.of("large",large)};
            for (int i=0;i<json.length;i++) {
                server.enqueue(new MockResponse().setBody(json[i]));
                assertThat(transport.send("{}".getBytes(StandardCharsets.UTF_8), invocation(server))).isEqualTo(expected[i]);
            }
            server.enqueue(new MockResponse().setBody(new String(fixture("openrouter-response.json"),StandardCharsets.UTF_8)));
            assertThat((Map<String,Object>)transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation(server)))
                    .containsKeys("id","provider","refusal","unknown","usage");
        }
    }

    @Test void errorCaptureIsBoundedUtf8AndDoesNotDrainRemainder() throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("€".repeat(100_000))
                    .throttleBody(70_000, 1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            TypeSafeHttpFailure failure = catchThrowableOfType(() -> transport.send("{}".getBytes(StandardCharsets.UTF_8), invocation(server)),TypeSafeHttpFailure.class);
            assertThat(failure.truncated).isTrue(); assertThat(failure.unavailable).isFalse();
            assertThat(failure.body.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(TypeSafeTransport.ERROR_LIMIT_BYTES);
            assertThat(failure.body).doesNotContain("�");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test void unreadableStatusBodyKeepsClassification() throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("broken-body").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            server.enqueue(new MockResponse().setBody("{}"));
            assertThat(transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation(server))).isEqualTo(Map.of());
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test void socketDisconnectUsesPackRetriesOnly() throws Exception {
        try (var server = new MockWebServer(); var transport = new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(new MockResponse().setBody("{}"));
            transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation(server));
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test void retryAfterIsBoundedAndInvalidValuesUseBackoff() {
        try (var transport = new TypeSafeTransport(FAST)) {
            assertThat(transport.retryDelay("0",1)).isZero();
            assertThat(transport.retryDelay("999999999999999999999999999999999999999999",1)).isEqualTo(FAST.retryAfterCap());
            assertThat(transport.retryDelay("-1",1)).isEqualTo(FAST.firstBackoff());
            assertThat(transport.retryDelay("garbage",2)).isEqualTo(FAST.secondBackoff());
            assertThat(transport.retryDelay(ZonedDateTime.now().plusDays(1).format(DateTimeFormatter.RFC_1123_DATE_TIME),1)).isEqualTo(FAST.retryAfterCap());
        }
    }

    @Test void retryAfterDelayIsAppliedAndCallerBodyMutationDoesNotChangeRetry() throws Exception {
        try(var server=new MockWebServer();var transport=new TypeSafeTransport(FAST)) {
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","99999999999999999"));
            server.enqueue(new MockResponse().setBody("{}"));
            byte[] body="{\"original\":true}".getBytes(StandardCharsets.UTF_8); byte[] frozen=body.clone();
            var invocation=invocation(server); var diagnostics=invocation.diagnostics();
            var retryStarted=new java.util.concurrent.atomic.AtomicLong();
            doAnswer(call -> {if(call.getArgument(0).equals("TypeSafe retry scheduled")) {java.util.Arrays.fill(body,(byte)'x');retryStarted.set(System.nanoTime());}return null;})
                    .when(diagnostics).warn(anyString(),any());
            transport.send(body,invocation);
            long retryElapsedMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-retryStarted.get());
            var first=server.takeRequest();var second=server.takeRequest();
            assertThat(first.getBody().readByteArray()).isEqualTo(frozen);
            assertThat(second.getBody().readByteArray()).isEqualTo(frozen);
            assertThat(retryElapsedMillis).isGreaterThanOrEqualTo(75).isLessThan(1500);
        }
    }

    @ParameterizedTest @ValueSource(ints={401,429})
    void totalTimeoutWhileCapturingErrorBodyDoesNotReclassifyKnownStatus(int status) throws Exception {
        var shortPolicy=new TransportPolicy(Duration.ofMillis(180),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),
                Duration.ofMillis(15),Duration.ofMillis(25),Duration.ofMillis(100));
        try(var server=new MockWebServer();var transport=new TypeSafeTransport(shortPolicy)) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("incomplete-error-body").throttleBody(1,50,TimeUnit.MILLISECONDS));
            server.enqueue(new MockResponse().setBody("{}"));
            var invocation=invocation(server);
            if(status==401) {
                var failure=catchThrowableOfType(() -> transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation),TypeSafeHttpFailure.class);
                assertThat(failure.unavailable).isTrue(); assertThat(failure.status).isEqualTo(401);
                assertThat(server.getRequestCount()).isEqualTo(1);
            } else {
                assertThat(transport.send("{}".getBytes(StandardCharsets.UTF_8),invocation)).isEqualTo(Map.of());
                assertThat(server.getRequestCount()).isEqualTo(2);
            }
        }
    }

    @Test void onePoolIsolatesConcurrentKeysEndpointsAndBodies() throws Exception {
        try (var first = new MockWebServer(); var second = new MockWebServer(); var transport = new TypeSafeTransport(FAST);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            Dispatcher dispatcher = new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    entered.countDown(); if (!release.await(2,TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return new MockResponse().setBody("{}");
                }
            };
            first.setDispatcher(dispatcher); second.setDispatcher(dispatcher);
            var a = invocation(first); var b = invocation(second);
            when(a.skillConfig()).thenReturn(Map.of("api_key","key-a","base_url",first.url("/a").toString()));
            when(b.skillConfig()).thenReturn(Map.of("api_key","key-b","base_url",second.url("/b").toString()));
            var fa=workers.submit(() -> transport.send(new byte[]{65},a)); var fb=workers.submit(() -> transport.send(new byte[]{66},b));
            try { assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
            fa.get(3,TimeUnit.SECONDS); fb.get(3,TimeUnit.SECONDS);
            var ra=first.takeRequest(); var rb=second.takeRequest();
            assertThat(ra.getHeader("Authorization")).isEqualTo("Bearer key-a"); assertThat(ra.getPath()).isEqualTo("/a/v1/systemone");
            assertThat(rb.getHeader("Authorization")).isEqualTo("Bearer key-b"); assertThat(rb.getPath()).isEqualTo("/b/v1/systemone");
            assertThat(ra.getBody().readByteArray()).isEqualTo(new byte[]{65}); assertThat(rb.getBody().readByteArray()).isEqualTo(new byte[]{66});
        }
    }
}
