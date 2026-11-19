package ai.loomspan.decisions.internal.typesafe;

import ai.loomspan.api.extensions.v1.ExecutorDiagnostics;
import ai.loomspan.api.extensions.v1.SkillExecutorInvocation;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Pack implementation detail. One pool and timer, request-local authentication. */
public final class TypeSafeTransport implements AutoCloseable {
    static final int ERROR_LIMIT_BYTES = 64 * 1024;
    private final TransportPolicy policy;
    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor watchdog;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public TypeSafeTransport() { this(TransportPolicy.DEFAULT); }

    TypeSafeTransport(TransportPolicy policy) {
        this.policy = policy;
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(100).setMaxConnPerRoute(20)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout(policy.connect()))
                        .setSocketTimeout(timeout(policy.inactivity())).build()).build();
        client = HttpClients.custom().setConnectionManager(pool)
                .disableAutomaticRetries().disableRedirectHandling().build();
        watchdog = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "loomspan-decisions-deadline");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.setRemoveOnCancelPolicy(true);
        watchdog.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        watchdog.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public Object send(byte[] preparedJson, SkillExecutorInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        checkLive(invocation);
        int maxAttempts = TypeSafeRequest.maxAttempts(invocation.skillConfig());
        TypeSafeSettings settings = TypeSafeSettings.capture(invocation.executorConfig());
        byte[] body = Objects.requireNonNull(preparedJson, "preparedJson").clone();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            checkLive(invocation);
            int number = attempt;
            diagnostic(invocation, false, "TypeSafe request", () -> Map.of(
                    "endpoint", settings.endpoint().toString(), "attempt", number,
                    "body", new String(body, StandardCharsets.UTF_8)));
            try {
                Object result = attempt(settings, body, invocation);
                diagnostic(invocation, false, "TypeSafe response", () -> {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("attempt", number);
                    data.put("response", result);
                    return data;
                });
                return result;
            } catch (TypeSafeHttpFailure failure) {
                checkLive(invocation);
                diagnostic(invocation, true, "TypeSafe HTTP failure", () -> Map.of(
                        "attempt", number, "status", failure.status, "body", failure.body,
                        "truncated", failure.truncated, "unavailable", failure.unavailable,
                        "captureLimitBytes", ERROR_LIMIT_BYTES));
                if (!failure.retryable() || attempt == maxAttempts) throw failure;
                retryWait(invocation, retryDelay(failure.retryAfter, attempt), number);
            } catch (IOException failure) {
                checkLive(invocation);
                diagnostic(invocation, true, "TypeSafe transport failure", () -> Map.of(
                        "attempt", number, "message", String.valueOf(failure.getMessage())));
                if (attempt == maxAttempts) throw new IllegalStateException("TypeSafe HTTP execution failed", failure);
                retryWait(invocation, retryDelay(null, attempt), number);
            }
        }
        throw new AssertionError("bounded attempts exhausted");
    }

    private Object attempt(TypeSafeSettings settings, byte[] body, SkillExecutorInvocation invocation) throws IOException {
        checkLive(invocation);
        Duration budget = invocation.remainingTime().map(value -> min(value, policy.total())).orElse(policy.total());
        HttpPost request = new HttpPost(settings.endpoint());
        request.setHeader("Authorization", "Bearer " + settings.apiKey());
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(timeout(min(policy.acquire(), budget)))
                .setResponseTimeout(timeout(min(policy.inactivity(), budget))).build());
        Guard guard = new Guard(invocation, request, budget);
        ScheduledFuture<?> timer;
        try {
            long interval = Math.min(TimeUnit.MILLISECONDS.toNanos(25), nanos(budget));
            timer = watchdog.scheduleAtFixedRate(guard::poll, Math.max(1, interval), Math.max(1, interval), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            request.cancel();
            throw new IllegalStateException("TypeSafe transport is closed", ex);
        }
        try {
            checkLive(invocation);
            byte[] response = client.execute(request, received -> {
                int status = received.getCode();
                if (status < 200 || status >= 300) {
                    var retryAfter = received.getFirstHeader("Retry-After");
                    String delay = retryAfter == null ? null : retryAfter.getValue();
                    InputStream errorBody;
                    try {
                        errorBody = received.getEntity() == null ? InputStream.nullInputStream() : received.getEntity().getContent();
                    } catch (IOException unavailable) {
                        request.cancel();
                        throw new TypeSafeHttpFailure(status, "", false, true, delay);
                    }
                    throw error(status, delay, errorBody, request);
                }
                try (InputStream stream = received.getEntity() == null ? InputStream.nullInputStream() : received.getEntity().getContent()) {
                    return stream.readAllBytes();
                }
            });
            guard.verify();
            // Successful decoding is deliberately outside the IOException retry classification.
            if (response.length == 0 || new String(response, StandardCharsets.UTF_8).isBlank()) {
                throw new IllegalStateException("TypeSafe returned an empty successful JSON body");
            }
            Object result = json.readValue(response, Object.class);
            guard.verify();
            return result;
        } catch (IOException ex) {
            guard.verify();
            throw ex;
        } finally {
            timer.cancel(false);
        }
    }

    private static TypeSafeHttpFailure error(int status, String retryAfter, InputStream stream, HttpPost request) {
        byte[] captured = new byte[0];
        boolean unavailable = false;
        boolean truncated = false;
        try {
            captured = stream.readNBytes(ERROR_LIMIT_BYTES + 1);
            truncated = captured.length > ERROR_LIMIT_BYTES;
        } catch (IOException ex) {
            unavailable = true;
        } finally {
            if (truncated || unavailable) request.cancel();
            try { stream.close(); } catch (IOException ignored) { unavailable = true; request.cancel(); }
        }
        return new TypeSafeHttpFailure(status, utf8Prefix(captured), truncated, unavailable, retryAfter);
    }

    private static String utf8Prefix(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer chars = CharBuffer.allocate(ERROR_LIMIT_BYTES);
        decoder.decode(ByteBuffer.wrap(bytes, 0, Math.min(bytes.length, ERROR_LIMIT_BYTES)), chars, false);
        chars.flip();
        String text = chars.toString();
        // Replacement characters may encode larger than malformed input bytes.
        int encoded = 0;
        int end = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int size = cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4;
            if (encoded + size > ERROR_LIMIT_BYTES) break;
            encoded += size;
            end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }

    Duration retryDelay(String value, int failedAttempt) {
        Duration fallback = failedAttempt == 1 ? policy.firstBackoff() : policy.secondBackoff();
        if (value == null) return fallback;
        try {
            if (value.matches("[0-9]+")) {
                var seconds = new java.math.BigInteger(value);
                if (seconds.compareTo(java.math.BigInteger.valueOf(policy.retryAfterCap().getSeconds() + 1)) > 0)
                    return policy.retryAfterCap();
                return min(Duration.ofSeconds(seconds.longValueExact()), policy.retryAfterCap());
            }
            Duration delay = Duration.between(java.time.Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : min(delay, policy.retryAfterCap());
        } catch (RuntimeException invalid) { return fallback; }
    }

    private void retryWait(SkillExecutorInvocation invocation, Duration delay, int attempt) {
        diagnostic(invocation, true, "TypeSafe retry scheduled", () -> Map.of(
                "failedAttempt", attempt, "delayMillis", delay.toMillis()));
        long start = System.nanoTime();
        long duration = nanos(delay);
        while (true) {
            checkLive(invocation);
            long left = duration - (System.nanoTime() - start);
            if (left <= 0) return;
            long slice = Math.min(left, TimeUnit.MILLISECONDS.toNanos(25));
            var remaining = invocation.remainingTime();
            if (remaining.isPresent()) slice = Math.min(slice, nanos(remaining.get()));
            try { TimeUnit.NANOSECONDS.sleep(Math.max(1, slice)); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TypeSafe invocation interrupted", ex);
            }
        }
    }

    private void checkLive(SkillExecutorInvocation invocation) {
        if (closed.get()) throw new IllegalStateException("TypeSafe transport is closed");
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("TypeSafe invocation interrupted");
        if (invocation.isCancelled()) throw new IllegalStateException("TypeSafe invocation cancelled");
        if (invocation.remainingTime().map(value -> value.isZero() || value.isNegative()).orElse(false))
            throw new IllegalStateException("TypeSafe execution deadline exhausted");
    }

    private static void diagnostic(SkillExecutorInvocation invocation, boolean warning, String message,
                                   Supplier<Object> prepare) {
        try {
            ExecutorDiagnostics diagnostics = invocation.diagnostics();
            Object data = prepare.get();
            if (warning) diagnostics.warn(message, data); else diagnostics.info(message, data);
        } catch (RuntimeException ignored) {
            // This contains only new diagnostic preparation/recording, never business work.
        }
    }

    private final class Guard {
        private final SkillExecutorInvocation invocation;
        private final HttpPost request;
        private final Thread caller = Thread.currentThread();
        private final long start = System.nanoTime();
        private final java.util.concurrent.atomic.AtomicLong deadline;
        private final AtomicReference<String> stop = new AtomicReference<>();

        Guard(SkillExecutorInvocation invocation, HttpPost request, Duration budget) {
            this.invocation = invocation;
            this.request = request;
            this.deadline = new java.util.concurrent.atomic.AtomicLong(nanos(budget));
        }

        void poll() {
            long elapsed = System.nanoTime() - start;
            var remaining = invocation.remainingTime();
            remaining.ifPresent(value -> {
                long left = nanos(value);
                long candidate = left > Long.MAX_VALUE - elapsed ? Long.MAX_VALUE : elapsed + left;
                deadline.accumulateAndGet(candidate, Math::min);
            });
            String reason = closed.get() ? "closed" : caller.isInterrupted() ? "interrupted"
                    : invocation.isCancelled() ? "cancelled"
                    : remaining.map(value -> value.isNegative() || value.isZero()).orElse(false)
                        ? "execution deadline exhausted"
                    : elapsed >= deadline.get() ? "request deadline exhausted" : null;
            if (reason != null && stop.compareAndSet(null, reason)) request.cancel();
        }

        void verify() throws IOException {
            poll();
            String reason = stop.get();
            if ("request deadline exhausted".equals(reason)) throw new SocketTimeoutException("TypeSafe " + reason);
            if (reason != null) throw new IllegalStateException("TypeSafe " + reason);
        }
    }

    private static Duration min(Duration a, Duration b) { return a.compareTo(b) < 0 ? a : b; }
    private static long nanos(Duration duration) {
        try { return Math.max(0, duration.toNanos()); } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    private static Timeout timeout(Duration duration) {
        long ns = nanos(duration);
        long millis = ns / 1_000_000 + (ns % 1_000_000 == 0 ? 0 : 1);
        return Timeout.ofMilliseconds(Math.max(1, millis));
    }

    int pendingDeadlineTasks() { return watchdog.getQueue().size(); }
    boolean isClosed() { return closed.get() && watchdog.isTerminated(); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { client.close(); }
            catch (IOException ex) { throw new IllegalStateException("TypeSafe transport close failed", ex); }
            finally {
                watchdog.shutdownNow();
                try { watchdog.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
