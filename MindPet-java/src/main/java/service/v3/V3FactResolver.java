package service.v3;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/** Normalizes duplicate/specificity/contradiction and temporal signals without deleting history. */
public final class V3FactResolver {

    private static final Set<String> ACTIONS = Set.of(
        "NEW", "DUPLICATE", "MORE_SPECIFIC", "CONTRADICTS", "SUPERSEDES");
    private static final Set<String> TEMPORAL = Set.of(
        "PERMANENT", "CURRENT", "TEMPORARY", "BOUNDED", "FUTURE", "ENDED", "UNKNOWN");

    private V3FactResolver() {}

    public record Resolution(
        String action,
        String temporalStatus,
        String validFrom,
        String validTo
    ) {}

    public static Resolution resolve(
            String action, String temporalStatus, String validFrom, String validTo) {
        String safeAction = member(action, ACTIONS, "NEW");
        String safeTemporal = member(temporalStatus, TEMPORAL, "UNKNOWN");
        String from = instantOrEmpty(validFrom);
        String to = instantOrEmpty(validTo);
        if (!to.isBlank() && safeTemporal.equals("PERMANENT")) safeTemporal = "BOUNDED";
        if (safeTemporal.equals("TEMPORARY") && to.isBlank()) {
            // Temporary remains explicitly temporary; it is never silently promoted to permanent.
            safeTemporal = "TEMPORARY";
        }
        return new Resolution(safeAction, safeTemporal, from, to);
    }

    private static String member(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static String instantOrEmpty(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return Instant.parse(value.trim()).toString();
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }
}
