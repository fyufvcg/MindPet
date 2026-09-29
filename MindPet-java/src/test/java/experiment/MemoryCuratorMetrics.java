package experiment;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free metrics collector used by replay tests. */
public final class MemoryCuratorMetrics {
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final Map<String, Long> durations = new LinkedHashMap<>();

    public void increment(String name) {
        counters.merge(name, 1L, Long::sum);
    }

    public void add(String name, long value) {
        counters.merge(name, value, Long::sum);
    }

    public Timer start(String name) {
        return new Timer(name, Instant.now());
    }

    public Map<String, Long> counters() { return Map.copyOf(counters); }

    public Map<String, Long> durations() { return Map.copyOf(durations); }

    public final class Timer implements AutoCloseable {
        private final String name;
        private final Instant started;
        private boolean closed;

        private Timer(String name, Instant started) {
            this.name = name;
            this.started = started;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            durations.merge(name, Duration.between(started, Instant.now()).toMillis(), Long::sum);
        }
    }
}
