package ai.loomspan.decisions.internal.typesafe;

import java.time.Duration;

/** Fixed pack policy; package access permits short deterministic test budgets. */
record TransportPolicy(Duration total, Duration inactivity, Duration acquire, Duration connect,
                       Duration firstBackoff, Duration secondBackoff, Duration retryAfterCap) {
    static final TransportPolicy DEFAULT = new TransportPolicy(Duration.ofSeconds(60), Duration.ofSeconds(30),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(250), Duration.ofMillis(500),
            Duration.ofSeconds(5));

    TransportPolicy {
        for (Duration value : new Duration[]{total, inactivity, acquire, connect, firstBackoff,
                secondBackoff, retryAfterCap}) {
            if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("positive policy durations required");
        }
    }
}
