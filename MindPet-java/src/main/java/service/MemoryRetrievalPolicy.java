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
    private final Clock clock;
    private final boolean refreshAccess;
    private final boolean applyRetention;

    @Autowired
    public MemoryRetrievalPolicy(Clock clock,
            @Value("${app.memory.retrieval.refresh-access:true}") boolean refreshAccess,
            @Value("${app.memory.retrieval.apply-retention:true}") boolean applyRetention) {
        this.clock = clock;
        this.refreshAccess = refreshAccess;
        this.applyRetention = applyRetention;
    }

    public static MemoryRetrievalPolicy defaults() {
        return new MemoryRetrievalPolicy(Clock.systemUTC(), true, true);
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
        return importance * Math.exp(-ageHours(accessed != null ? accessed : created) / (layer == 2 ? 121.0 : 25.0));
    }
    public boolean visible(Timestamp accessed, Timestamp created, double importance, int layer) {
        return !applyRetention || rawRetention(accessed, created, importance, layer) > 0.1;
    }
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
