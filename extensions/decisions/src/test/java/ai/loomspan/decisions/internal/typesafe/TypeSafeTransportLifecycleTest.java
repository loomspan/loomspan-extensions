package ai.loomspan.decisions.internal.typesafe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ai.loomspan.decisions.internal.typesafe.TransportTestSupport.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TypeSafeTransportLifecycleTest {
    private static final byte[] BODY = new byte[]{123,125};
    private static TransportPolicy budget(Duration total) {
        return new TransportPolicy(total, Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofMillis(200));
    }

    @ParameterizedTest @ValueSource(strings={"cancelled","interrupted","expired"})
    void stoppedInvocationNeverSends(String reason) throws Exception {
        try (var server=new MockWebServer(); var transport=new TypeSafeTransport(FAST)) {
            var invocation=invocation(server);
            if(reason.equals("cancelled")) when(invocation.isCancelled()).thenReturn(true);
            if(reason.equals("expired")) when(invocation.remainingTime()).thenReturn(Optional.of(Duration.ZERO));
            if(reason.equals("interrupted")) Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> transport.send(BODY,invocation)).isInstanceOf(IllegalStateException.class);
                if(reason.equals("interrupted")) assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(server.getRequestCount()).isZero();
            } finally { Thread.interrupted(); }
        }
    }

    @ParameterizedTest @ValueSource(strings={"cancelled","interrupted","expired"})
    void retryWaitCooperatesAndPreservesInterruption(String reason) throws Exception {
        try (var server=new MockWebServer(); var transport=new TypeSafeTransport(budget(Duration.ofSeconds(3)));
             var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            server.enqueue(new MockResponse().setResponseCode(429)); server.enqueue(new MockResponse().setBody("{}"));
            var invocation=invocation(server); var cancelled=new AtomicBoolean(); var expired=new AtomicBoolean();
            when(invocation.isCancelled()).thenAnswer(call -> cancelled.get());
            when(invocation.remainingTime()).thenAnswer(call -> expired.get()?Optional.of(Duration.ZERO):Optional.empty());
            var entered=new CountDownLatch(1); var caller=new AtomicReference<Thread>(); var interrupted=new AtomicBoolean();
            var diagnostics=invocation.diagnostics();
            doAnswer(call -> {if(call.getArgument(0).equals("TypeSafe retry scheduled")) entered.countDown(); return null;})
                    .when(diagnostics).warn(anyString(),any());
            var result=workers.submit(() -> {
                caller.set(Thread.currentThread());
                try {transport.send(BODY,invocation); return null;} catch(RuntimeException ex) {interrupted.set(Thread.currentThread().isInterrupted()); return ex;}
            });
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            if(reason.equals("cancelled")) cancelled.set(true);
            if(reason.equals("expired")) expired.set(true);
            if(reason.equals("interrupted")) caller.get().interrupt();
            assertThat(result.get(2,TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
            if(reason.equals("interrupted")) assertThat(interrupted).isTrue();
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test void progressingBodyCannotEscapeTotalDeadlineAndPackTimeoutRetriesAreBounded() throws Exception {
        try(var server=new MockWebServer();var transport=new TypeSafeTransport(budget(Duration.ofMillis(180)))) {
            for(int i=0;i<3;i++) server.enqueue(new MockResponse().setBody("["+"0,".repeat(500)+"0]")
                    .throttleBody(10,40,TimeUnit.MILLISECONDS));
            long start=System.nanoTime();
            assertThatThrownBy(() -> transport.send(BODY,invocation(server))).hasCauseInstanceOf(java.net.SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
            assertThat(server.getRequestCount()).isEqualTo(3);
            assertThat(transport.pendingDeadlineTasks()).isZero();
        }
    }

    @Test void originalExecutionBudgetCapsRequestsAndPreventsRetry() throws Exception {
        try(var server=new MockWebServer();var transport=new TypeSafeTransport(budget(Duration.ofSeconds(3)))) {
            server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(2,TimeUnit.SECONDS));
            var invocation=invocation(server); long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(160);
            when(invocation.remainingTime()).thenAnswer(call -> Optional.of(Duration.ofNanos(Math.max(0,deadline-System.nanoTime()))));
            long start=System.nanoTime();
            assertThatThrownBy(() -> transport.send(BODY,invocation)).hasMessageContaining("deadline");
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"cancelled","shortened","interrupted","closed"})
    void liveChangesAbortPhysicalRequestAndRemoveTimer(String change) throws Exception {
        try(var server=new MockWebServer();var transport=new TypeSafeTransport(budget(Duration.ofSeconds(3)));
            var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            server.enqueue(new MockResponse().setBody("["+"0,".repeat(500)+"0]").throttleBody(1,25,TimeUnit.MILLISECONDS));
            var invocation=invocation(server); var cancelled=new AtomicBoolean(); var expiry=new AtomicLong(Long.MAX_VALUE);
            var caller=new AtomicReference<Thread>(); var interrupted=new AtomicBoolean();
            when(invocation.isCancelled()).thenAnswer(call -> cancelled.get());
            when(invocation.remainingTime()).thenAnswer(call -> expiry.get()==Long.MAX_VALUE?Optional.empty():
                    Optional.of(Duration.ofNanos(Math.max(0,expiry.get()-System.nanoTime()))));
            var future=workers.submit(() -> {caller.set(Thread.currentThread());try {return transport.send(BODY,invocation);}
                catch(RuntimeException ex) {interrupted.set(Thread.currentThread().isInterrupted());return ex;}});
            assertThat(server.takeRequest(2,TimeUnit.SECONDS)).isNotNull();
            switch(change) {
                case "cancelled" -> cancelled.set(true);
                case "shortened" -> expiry.set(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(90));
                case "interrupted" -> caller.get().interrupt();
                case "closed" -> transport.close();
            }
            assertThat(future.get(2,TimeUnit.SECONDS)).isInstanceOf(RuntimeException.class);
            if(change.equals("interrupted")) assertThat(interrupted).isTrue();
            assertThat(transport.pendingDeadlineTasks()).isZero();
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test void closeAdmissionRaceCannotOrphanWork() throws Exception {
        try(var server=new MockWebServer();var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){return new MockResponse().setBody("{}");}});
            for(int i=0;i<12;i++) {
                var transport=new TypeSafeTransport(FAST); var invocation=invocation(server); var start=new CountDownLatch(1);
                var future=workers.submit(() -> {start.await();try{return transport.send(BODY,invocation);}catch(RuntimeException ex){return ex;}});
                start.countDown(); transport.close(); future.get(2,TimeUnit.SECONDS); transport.close();
                assertThat(transport.isClosed()).isTrue(); assertThat(transport.pendingDeadlineTasks()).isZero();
                assertThatThrownBy(() -> transport.send(BODY,invocation)).hasMessageContaining("closed");
            }
        }
    }
}
