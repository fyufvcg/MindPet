package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 日期计算工具，支持日期的加减运算以及两个日期之间的差值计算。
 * 支持精确到秒的日期时间格式，以及天、周、月、年、小时、分钟等多种时间单位。
 */
@Component
public class DateCalcTool {

    /**
     * 执行日期计算操作。
     *
     * @param date      日期字符串
     * @param operation 操作类型（add/subtract/diff）
     * @param amount    偏移量
     * @param unit      偏移单位（days/week/months/years/hours/minutes）
     * @param otherDate 对比日期（用于 diff 操作）
     * @return 计算结果字符串，描述日期偏移或差值
     */
    @Tool(description = "Add/subtract dates or calculate date difference")
    public String dateCalculate(
            @ToolParam(description = "Date/time, e.g. 2026-07-22 or 2026-07-22 10:30:00") String date,
            @ToolParam(description = "Operation: add, subtract, diff") String operation,
            @ToolParam(description = "Offset amount, default 1") Integer amount,
            @ToolParam(description = "Unit: days, weeks, months, years, hours, minutes") String unit,
            @ToolParam(description = "Other date used for diff") String otherDate) {

        String op = lower(operation, "add");
        String ut = lower(unit, "days");
        int amt = amount != null ? amount : 1;

        if (date == null || date.isEmpty()) {
            return "Error: missing date";
        }

        try {
            if ("diff".equals(op)) {
                if (otherDate == null || otherDate.isEmpty()) {
                    return "Error: missing other_date";
                }
                return diff(date, otherDate);
            }
            return shift(date, op, amt, ut);
        } catch (Exception e) {
            return "Date calculation failed: " + e.getMessage();
        }
    }

    /**
     * 对日期进行偏移（加减）运算，支持日期类型和日期时间类型。
     */
    private String shift(String date, String operation, int amount, String unit) {
        boolean subtract = "subtract".equals(operation);
        int delta = subtract ? -amount : amount;
        if (hasTime(date)) {
            LocalDateTime dt = parseDateTime(date);
            LocalDateTime result = switch (unit) {
                case "hours" -> dt.plusHours(delta);
                case "minutes" -> dt.plusMinutes(delta);
                case "weeks" -> dt.plusWeeks(delta);
                case "months" -> dt.plusMonths(delta);
                case "years" -> dt.plusYears(delta);
                default -> dt.plusDays(delta);
            };
            return format(result);
        }
        LocalDate d = parseDate(date);
        LocalDate result = switch (unit) {
            case "weeks" -> d.plusWeeks(delta);
            case "months" -> d.plusMonths(delta);
            case "years" -> d.plusYears(delta);
            default -> d.plusDays(delta);
        };
        return result.toString();
    }

    /**
     * 计算两个日期之间的差值。
     */
    private String diff(String date1, String date2) {
        if (hasTime(date1) || hasTime(date2)) {
            LocalDateTime d1 = parseDateTime(date1);
            LocalDateTime d2 = parseDateTime(date2);
            Duration duration = Duration.between(d1, d2).abs();
            long days = duration.toDays();
            long hours = duration.toHoursPart();
            long minutes = duration.toMinutesPart();
            return "Difference: " + days + " days " + hours + " hours " + minutes + " minutes";
        }
        LocalDate d1 = parseDate(date1);
        LocalDate d2 = parseDate(date2);
        return "Difference: " + Math.abs(ChronoUnit.DAYS.between(d1, d2)) + " days";
    }

    /** 判断日期字符串是否包含时间信息 */
    private boolean hasTime(String value) {
        return value.contains(":") || value.length() > 10;
    }

    /** 解析日期字符串（仅日期部分，格式 yyyy-MM-dd） */
    private LocalDate parseDate(String value) {
        return LocalDate.parse(value.substring(0, 10));
    }

    /**
     * 解析日期时间字符串，支持多种格式：
     * yyyy-MM-dd、yyyy-MM-dd HH:mm、yyyy-MM-dd HH:mm:ss 以及 ISO 格式。
     */
    private LocalDateTime parseDateTime(String value) {
        String v = value.trim();
        if (v.length() == 10) {
            return LocalDate.parse(v).atStartOfDay();
        }
        if (v.length() == 16) {
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        if (v.length() == 19 && v.charAt(10) == ' ') {
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return LocalDateTime.parse(v.replace(' ', 'T'));
    }

    /** 将日期时间格式化为 yyyy-MM-dd HH:mm:ss 字符串 */
    private String format(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 安全地将 Object 转为小写字符串，若为空则返回默认值。
     */
    private String lower(String v, String def) {
        return v == null || v.isEmpty() ? def : v.toLowerCase();
    }
}
