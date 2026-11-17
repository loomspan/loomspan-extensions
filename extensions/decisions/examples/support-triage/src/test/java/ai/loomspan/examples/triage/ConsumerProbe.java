package ai.loomspan.examples.triage;

import ai.loomspan.api.SkillExecutor;
import ai.loomspan.api.extensions.v1.SkillExecutorInvocation;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;

/** Test instrumentation around the discovered installed pack, never a fake HTTP client. */
public final class ConsumerProbe {
    final ListableBeanFactory beans;
    final CountDownLatch entered = new CountDownLatch(1), returned = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
    volatile SkillExecutorInvocation call;
    volatile Duration entryRemaining;
    volatile Thread worker;
    volatile boolean holdBefore, holdAfter, interruptBefore, interruptedOnExit, oversizedMessage;
    volatile UnaryOperator<SkillExecutorInvocation> instrument = UnaryOperator.identity();
    ConsumerProbe(ListableBeanFactory beans) { this.beans = beans; }

    @SkillExecutor(name = "probe", config = "typesafe")
    public String execute(SkillExecutorInvocation invocation) throws Throwable {
        call = invocation; worker = Thread.currentThread(); entryRemaining = invocation.remainingTime().orElse(null); entered.countDown();
        try {
            if (holdBefore) awaitIgnoringInterrupt();
            if (interruptBefore) worker.interrupt();
            if (oversizedMessage) invocation.diagnostics().warn("雪".repeat(4000), java.util.Map.of("source", "authored Unicode"));
            Object bean = null; Method method = null;
            for (String name : beans.getBeanDefinitionNames()) {
                Class<?> type = beans.getType(name);
                if (type == null) continue;
                for (Method candidate : type.getMethods()) {
                    SkillExecutor annotation = candidate.getAnnotation(SkillExecutor.class);
                    if (annotation != null && annotation.name().equals("typesafe")) { bean = beans.getBean(name); method = candidate; }
                }
            }
            if (method == null) throw new AssertionError("Installed typesafe executor not discovered");
            String result;
            try { result = (String) method.invoke(bean, instrument.apply(invocation)); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            returned.countDown();
            if (holdAfter) awaitIgnoringInterrupt();
            invocation.diagnostics().error("probe after delegate", java.util.Map.of("physicalReturn", true));
            return result;
        } finally { interruptedOnExit = Thread.currentThread().isInterrupted(); stopped.countDown(); }
    }
    private void awaitIgnoringInterrupt() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (release.getCount() != 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("Probe release timed out");
            try { release.await(10, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Deliberate physical-work retention probe. */ }
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Configuration {
        @Bean ConsumerProbe consumerProbe(ListableBeanFactory beans) { return new ConsumerProbe(beans); }
    }
}
