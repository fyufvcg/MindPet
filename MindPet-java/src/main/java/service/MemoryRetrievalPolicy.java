package service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.format.DateTimeFormatter;

/** Runtime controls for retrieval; ingestion and compaction keep their own policies. */
@Component
public class MemoryRetrievalPolicy {
    public static final double RETENTION_MIN = 0.1;
    private static final double IMPORTANT_DECAY_HOURS = 121.0;
    private static final double REGULAR_DECAY_HOURS = 25.0;

    private final Clock clock;
    private final boolean refreshAccess;
    private final boolean applyRetention;

    @Autowired
    public MemoryRetrievalPolicy(Clock clock,
            // Retrieval-time access refresh is opt-in. A stale mapped unit must not
            // become immortal merely because another route happened to select it.
            @Value("${app.memory.retrieval.refresh-access:false}") boolean refreshAccess,
            @Value("${app.memory.retrieval.apply-retention:true}") boolean applyRetention) {
        this.clock = clock;
        this.refreshAccess = refreshAccess;
        this.applyRetention = applyRetention;
    }

    public static MemoryRetrievalPolicy defaults() {
        return new MemoryRetrievalPolicy(Clock.systemUTC(), false, true);
    }

    public boolean refreshAccess() { return refreshAccess; }
    public boolean applyRetention() { return applyRetention; }
    public long nowMillis() { return clock.millis(); }
    public String sqlNow() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
            .format(clock.instant());
    }
    public double ageHours(Timestamp timestamp) {
        return timestamp == null ? 0 : Math.max(0, clock.millis() - timestamp.getTime()) / 3_600_000.0;
    }
    public double rawRetention(Timestamp accessed, Timestamp created, double importance, int layer) {
        if (accessed == null && created == null) return 0;
        return importance * Math.exp(-ageHours(accessed != null ? accessed : created) / decayHours(layer));
    }
    public boolean visible(Timestamp accessed, Timestamp created, double importance, int layer) {
        return !applyRetention || rawRetention(accessed, created, importance, layer) > RETENTION_MIN;
    }
    public double decayHours(int layer) { return layer == 2 ? IMPORTANT_DECAY_HOURS : REGULAR_DECAY_HOURS; }
    public double retentionMinimum() { return RETENTION_MIN; }
    /** SQLite CURRENT_TIMESTAMP and sqlNow are UTC, irrespective of the JDBC host timezone. */
    public Timestamp readStorageTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new Timestamp(Long.parseLong(value)); }
        catch (NumberFormatException ignored) { }
        String iso = value.trim().replace(' ', 'T');
        try { return Timestamp.from(java.time.Instant.parse(iso)); }
        catch (java.time.DateTimeException ignored) { }
        try { return Timestamp.from(java.time.OffsetDateTime.parse(iso).toInstant()); }
        catch (java.time.DateTimeException ignored) { }
        try { return Timestamp.from(java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC)); }
        catch (java.time.DateTimeException ignored) { return null; }
    }
}
