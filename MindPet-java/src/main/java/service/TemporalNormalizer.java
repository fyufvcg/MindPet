package service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;

/** Deterministic normalization for dates mentioned in a completed turn. */
public final class TemporalNormalizer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private TemporalNormalizer() {}

    public record Resolution(String rawExpression, String normalizedStart,
                             String normalizedEnd, String precision,
                             String status, String anchor, String timezone) {
        public boolean resolved() { return "resolved".equals(status); }
    }

    public static Resolution resolve(String expression, Instant occurredAt, ZoneId zone) {
        String raw = expression == null ? "" : expression.trim();
        ZoneId safeZone = zone == null ? ZoneId.systemDefault() : zone;
        if (raw.isBlank()) return unresolved(raw, safeZone, "unresolved");

        ZonedDateTime reference = (occurredAt == null ? Instant.now() : occurredAt).atZone(safeZone);
        LocalDate base = reference.toLocalDate();

        if (containsAny(raw, "过几天", "过些天", "最近", "以后", "下个月左右", "几天后")) {
            return unresolved(raw, safeZone, "ambiguous");
        }

        LocalDate date = explicitDate(raw, base);
        if (date != null) return resolved(raw, date, null, "day", safeZone, "explicit_date");

        int offset = relativeOffset(raw);
        if (offset != Integer.MIN_VALUE) {
            return resolved(raw, base.plusDays(offset), null, "day", safeZone, "message_occurred_at");
        }

        DayOfWeek weekday = weekday(raw);
        if (weekday != null && containsAny(raw, "下周", "下个星期", "下礼拜")) {
            LocalDate next = base.with(TemporalAdjusters.next(weekday));
            return resolved(raw, next, null, "day", safeZone, "message_occurred_at");
        }

        return unresolved(raw, safeZone, "unresolved");
    }

    public static Resolution resolveContent(String content, Instant occurredAt, ZoneId zone) {
        String text = content == null ? "" : content;
        String marker = extractMarker(text);
        return resolve(marker, occurredAt, zone);
    }

    private static String extractMarker(String text) {
        String[] markers = {"大前天", "前天", "昨天", "今天", "明天", "后天", "大后天",
            "下周一", "下周二", "下周三", "下周四", "下周五", "下周六", "下周日",
            "下周天", "过几天", "最近", "以后"};
        for (String marker : markers) if (text.contains(marker)) return marker;
        java.util.regex.Matcher iso = java.util.regex.Pattern
            .compile("(?<!\\d)(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}月\\d{1,2}日)(?!\\d)")
            .matcher(text);
        return iso.find() ? iso.group() : "";
    }

    private static LocalDate explicitDate(String value, LocalDate base) {
        java.util.regex.Matcher iso = java.util.regex.Pattern
            .compile("(?<!\\d)(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?!\\d)").matcher(value);
        if (iso.find()) {
            try { return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))); }
            catch (RuntimeException ignored) { return null; }
        }
        java.util.regex.Matcher chinese = java.util.regex.Pattern
            .compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日").matcher(value);
        if (chinese.find()) {
            try {
                int year = chinese.group(1) == null ? base.getYear() : Integer.parseInt(chinese.group(1));
                return LocalDate.of(year, Integer.parseInt(chinese.group(2)), Integer.parseInt(chinese.group(3)));
            } catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    private static int relativeOffset(String value) {
        if (value.contains("大前天")) return -3;
        if (value.contains("前天")) return -2;
        if (value.contains("昨天") || value.contains("昨日")) return -1;
        if (value.contains("今天") || value.contains("今日")) return 0;
        if (value.contains("大后天")) return 3;
        if (value.contains("后天") || value.contains("後天")) return 2;
        if (value.contains("明天") || value.contains("明日")) return 1;
        return Integer.MIN_VALUE;
    }

    private static DayOfWeek weekday(String value) {
        if (value.contains("一")) return DayOfWeek.MONDAY;
        if (value.contains("二")) return DayOfWeek.TUESDAY;
        if (value.contains("三")) return DayOfWeek.WEDNESDAY;
        if (value.contains("四")) return DayOfWeek.THURSDAY;
        if (value.contains("五")) return DayOfWeek.FRIDAY;
        if (value.contains("六")) return DayOfWeek.SATURDAY;
        if (value.contains("日") || value.contains("天")) return DayOfWeek.SUNDAY;
        return null;
    }

    private static Resolution resolved(String raw, LocalDate start, LocalDate end,
                                       String precision, ZoneId zone, String anchor) {
        return new Resolution(raw, start.format(DATE), end == null ? "" : end.format(DATE),
            precision, "resolved", anchor, zone.getId());
    }

    private static Resolution unresolved(String raw, ZoneId zone, String status) {
        return new Resolution(raw, "", "", "unknown", status,
            "message_occurred_at", zone.getId());
    }

    private static boolean containsAny(String value, String... options) {
        for (String option : options) if (value.contains(option)) return true;
        return false;
    }
}
