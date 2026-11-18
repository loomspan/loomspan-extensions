package ai.loomspan.examples.triage;

import ai.loomspan.api.SkillDocument;
import ai.loomspan.api.extensions.v1.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ai.loomspan.examples.triage.ConsumerHarness.*;

class LifecycleConsumerTest {
    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) TimeUnit.MILLISECONDS.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    static void probe(ConsumerHarness h) {
        h.publish(List.of(new SkillDocument("decision", transformedLeaf("decision", JSON.writeValueAsString(nativeRequest("state", "noul"))).yaml()
                .replace("executor: typesafe", "executor: probe"))), h.settings());
    }
    static Thread caller(ConsumerHarness h, AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().unstarted(() -> {
            authenticate(); try { h.invoke(Map.of()); } catch (Throwable ex) { failure.set(ex); }
            finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        });
    }
    @Test void liveCallerCancellationBeforeDelegateAndInterruptedWorkerPreventFirstSend() throws Exception {
        for (boolean interruptWorker : List.of(false, true)) {
            try (var h = new ConsumerHarness()) {
                probe(h); h.probe.holdBefore = !interruptWorker; h.probe.interruptBefore = interruptWorker;
                var failure = new AtomicReference<Throwable>(); Thread caller = caller(h, failure);
                try {
                    caller.start(); assertThat(h.probe.entered.await(5, TimeUnit.SECONDS)).isTrue();
                    if (!interruptWorker) { caller.interrupt(); await(() -> h.probe.call.isCancelled()); h.probe.release.countDown(); }
                    assertThat(h.probe.stopped.await(5, TimeUnit.SECONDS)).isTrue(); caller.join(5000);
                    assertThat(caller.isAlive()).isFalse(); assertThat(failure.get()).isNotNull();
                    assertThat(h.provider.getRequestCount()).isZero();
                    if (interruptWorker) assertThat(h.probe.interruptedOnExit).isTrue();
                    else assertThat(h.probe.call.isCancelled()).isTrue();
                } finally { h.probe.release.countDown(); caller.join(5000); }
            }
        }
    }
    @Test void cancellationAndInterruptionDuringRetryWaitPreventAnotherSend() throws Exception {
        for (boolean interruptWorker : List.of(false, true)) {
            try (var h = new ConsumerHarness()) {
                probe(h); var retry = new CountDownLatch(1);
                h.probe.instrument = real -> retrySignal(real, retry);
                h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "10").setBody("busy"));
                var failure = new AtomicReference<Throwable>(); Thread caller = caller(h, failure);
                try {
                    caller.start(); assertThat(retry.await(5, TimeUnit.SECONDS)).isTrue();
                    if (interruptWorker) h.probe.worker.interrupt(); else caller.interrupt();
                    assertThat(h.probe.stopped.await(5, TimeUnit.SECONDS)).isTrue(); caller.join(5000);
                    assertThat(caller.isAlive()).isFalse(); assertThat(failure.get()).isNotNull();
                    assertThat(h.provider.getRequestCount()).isOne();
                    if (interruptWorker) assertThat(h.probe.interruptedOnExit).isTrue();
                } finally { caller.interrupt(); caller.join(5000); }
            }
        }
    }
    static SkillExecutorInvocation retrySignal(SkillExecutorInvocation real, CountDownLatch retry) {
        ExecutorDiagnostics diagnostics = mock(ExecutorDiagnostics.class, answer -> {
            Object result = answer.getMethod().invoke(real.diagnostics(), answer.getArguments());
            if (answer.getArguments().length > 0 && "TypeSafe retry scheduled".equals(answer.getArgument(0))) retry.countDown();
            return result;
        });
        return mock(SkillExecutorInvocation.class, answer -> answer.getMethod().getName().equals("diagnostics") ? diagnostics
                : answer.getMethod().invoke(real, answer.getArguments()));
    }
    @Test void retryWaitUsesOriginalDeadlineAndExpiredAccessorIsZero() throws Exception {
        try (var h = new ConsumerHarness("loomspan.session.mission-timeout=700ms")) {
            probe(h); var retry = new CountDownLatch(1); h.probe.instrument = real -> retrySignal(real, retry);
            h.provider.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "10").setBody("busy"));
            var failure = new AtomicReference<Throwable>(); Thread caller = caller(h, failure);
            caller.start(); assertThat(retry.await(5, TimeUnit.SECONDS)).isTrue();
            Duration remaining = h.probe.call.remainingTime().orElseThrow();
            assertThat(remaining).isPositive().isLessThan(Duration.ofMillis(700));
            caller.join(5000); assertThat(caller.isAlive()).isFalse(); assertThat(failure.get()).isNotNull();
            assertThat(h.probe.stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.probe.call.remainingTime()).contains(Duration.ZERO); assertThat(h.provider.getRequestCount()).isOne();
        }
    }
    @Test void progressingBodyIsCappedByAlreadySpentParentExecutionBudget() throws Exception {
        try (var h = new ConsumerHarness("loomspan.session.mission-timeout=1800ms")) {
            var leaf = new SkillDocument("decision", transformedLeaf("decision", JSON.writeValueAsString(nativeRequest("state", "noul"))).yaml().replace("executor: typesafe", "executor: probe"));
            h.publish(List.of(leaf, new SkillDocument("parent", "name: parent\ndescription: Parent\nmodel: local\nallowed_skills: [{name: decision}]\n")), h.settings());
            h.planner.enqueue(AccountingConsumerTest.nativeResponse(List.of(AccountingConsumerTest.functionCall()), false).setBodyDelay(650, TimeUnit.MILLISECONDS));
            h.response("{\"slow\":\"" + "x".repeat(200) + "\"}");
            // A byte every 40ms progresses well below the inactivity limit but cannot finish in the remaining budget.
            h.provider.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("{\"slow\":\"" + "x".repeat(200) + "\"}").throttleBody(1, 40, TimeUnit.MILLISECONDS);
            }});
            long start = System.nanoTime();
            assertThatThrownBy(() -> h.skills.invoke("parent", Map.of(), h.views::add)).isNotNull();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            assertThat(h.probe.entryRemaining).isPositive().isLessThan(Duration.ofMillis(1500));
            assertThat(h.probe.stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.probe.call.remainingTime()).contains(Duration.ZERO); assertThat(h.provider.getRequestCount()).isOne();
        }
    }
    @Test void logicalTimeoutFencesLatePhysicalReturnAndRetainsPublishedGeneration() throws Exception {
        try (var h = new ConsumerHarness("loomspan.session.mission-timeout=800ms")) {
            var retired = new CopyOnWriteArrayList<String>();
            try (var registration = h.reloader.onGenerationRetired(retired::add)) {
                probe(h); String generation = h.reloader.snapshot("default").generationId();
                h.probe.holdAfter = true; h.response("{\"late\":true}");
                var failure = new AtomicReference<Throwable>(); Thread caller = caller(h, failure);
                try {
                    caller.start(); assertThat(h.probe.returned.await(5, TimeUnit.SECONDS)).isTrue();
                    h.publish(List.of(transformedLeaf("decision", JSON.writeValueAsString(nativeRequest("new", "noul")))), h.settings());
                    caller.join(5000); assertThat(caller.isAlive()).isFalse(); assertThat(failure.get()).isNotNull();
                    assertThat(h.probe.stopped.getCount()).isOne(); assertThat(retired).doesNotContain(generation);
                    assertThat(h.probe.call.remainingTime()).contains(Duration.ZERO);
                    PathSnapshot snapshot = new PathSnapshot(h.traceFile(h.views.getLast().sessionId()));
                    assertThat(snapshot.text).doesNotContain("probe after delegate");
                    h.probe.release.countDown(); assertThat(h.probe.stopped.await(5, TimeUnit.SECONDS)).isTrue();
                    await(() -> retired.contains(generation));
                    assertThat(retired.stream().filter(generation::equals)).hasSize(1);
                    assertThat(Files.readString(snapshot.path)).isEqualTo(snapshot.text);
                    assertThat(h.trace().getLast().path("metadata").path("outcome").asText()).isNotEqualTo("SUCCEEDED");
                } finally { h.probe.release.countDown(); caller.join(5000); }
            }
        }
    }
    private record PathSnapshot(java.nio.file.Path path, String text) {
        PathSnapshot(java.nio.file.Path path) throws Exception { this(path, Files.readString(path)); }
    }
    @Test void applicationShutdownStopsActiveTransportAndOwnedDeadlineThread() throws Exception {
        try (var h = new ConsumerHarness("loomspan.shutdown.timeout=400ms")) {
            probe(h); h.provider.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var failure = new AtomicReference<Throwable>(); Thread caller = caller(h, failure);
            caller.start(); assertThat(h.take()).isNotNull();
            await(() -> Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("loomspan-decisions-deadline") && t.isAlive()));
            h.context.close(); caller.join(5000);
            assertThat(caller.isAlive()).isFalse(); assertThat(failure.get()).isNotNull();
            assertThat(h.probe.stopped.await(5, TimeUnit.SECONDS)).isTrue(); assertThat(h.provider.getRequestCount()).isOne();
            await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("loomspan-decisions-deadline") && t.isAlive()));
        }
    }
}
