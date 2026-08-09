package service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves relative date expressions when a memory is created. */
public final class TemporalMemory {

    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?!\\d)");
    private static final Pattern CHINESE_DATE = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern TIME = Pattern.compile("(凌晨|早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})(?:点|时|:)(?:(\\d{1,2})分?)?");

    private TemporalMemory() {}

    public record Resolved(LocalDate eventDate, LocalDateTime eventAt,
                           String timezone, String precision) {
        public boolean hasValue() {
            return eventDate != null || eventAt != null;
        }
    }

    public static Resolved resolve(String content, Instant reference, ZoneId zone) {
        if (content == null || content.isBlank()) return empty(zone);
        ZoneId safeZone = zone == null ? ZoneId.systemDefault() : zone;
        LocalDate baseDate = (reference == null ? Instant.now() : reference)
            .atZone(safeZone).toLocalDate();

        LocalDate date = explicitDate(content, baseDate);
        if (date == null) {
            int offset = relativeOffset(content);
            if (offset == Integer.MIN_VALUE) return empty(safeZone);
            date = baseDate.plusDays(offset);
        }

        LocalTime time = time(content);
        LocalDateTime eventAt = time == null ? null : LocalDateTime.of(date, time);
        return new Resolved(date, eventAt, safeZone.getId(), time == null ? "date" : "minute");
    }

    public static String relativeLabel(LocalDate eventDate, ZoneId zone) {
        if (eventDate == null) return "";
        LocalDate today = LocalDate.now(zone == null ? ZoneId.systemDefault() : zone);
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, eventDate);
        return switch ((int) days) {
            case -1 -> "昨天";
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            default -> days < 0 ? "已过期" : days + "天后";
        };
    }

    private static LocalDate explicitDate(String content, LocalDate baseDate) {
        Matcher iso = ISO_DATE.matcher(content);
        if (iso.find()) {
            try {
                return LocalDate.of(Integer.parseInt(iso.group(1)),
                    Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            } catch (RuntimeException ignored) {}
        }

        Matcher chinese = CHINESE_DATE.matcher(content);
        if (chinese.find()) {
            try {
                int year = chinese.group(1) == null ? baseDate.getYear() : Integer.parseInt(chinese.group(1));
                return LocalDate.of(year, Integer.parseInt(chinese.group(2)), Integer.parseInt(chinese.group(3)));
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static int relativeOffset(String content) {
        if (containsAny(content, "大前天")) return -3;
        if (containsAny(content, "前天")) return -2;
        if (containsAny(content, "昨天", "昨日")) return -1;
        if (containsAny(content, "今天", "今日")) return 0;
        if (containsAny(content, "大后天")) return 3;
        if (containsAny(content, "后天", "後天")) return 2;
        if (containsAny(content, "明天", "明日")) return 1;
        return Integer.MIN_VALUE;
    }

    private static LocalTime time(String content) {
        Matcher matcher = TIME.matcher(content);
        if (!matcher.find()) return null;
        try {
            String period = matcher.group(1);
            int hour = Integer.parseInt(matcher.group(2));
            int minute = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            if (hour > 23 || minute > 59) return null;
            if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
            if ("中午".equals(period) && hour < 11) hour += 12;
            return LocalTime.of(hour, minute);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private static Resolved empty(ZoneId zone) {
        ZoneId safeZone = zone == null ? ZoneId.systemDefault() : zone;
        return new Resolved(null, null, safeZone.getId(), "none");
    }
}
