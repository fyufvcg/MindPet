package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimezoneConvertTool {

    @Tool(description = "Convert datetime between timezones")
    public String convertTimezone(
            @ToolParam(description = "Datetime, e.g. 2026-07-22 08:30:00") String datetime,
            @ToolParam(description = "Source timezone, e.g. Asia/Shanghai") String fromZone,
            @ToolParam(description = "Target timezone, e.g. UTC") String toZone) {

        if (datetime == null || fromZone == null || toZone == null) {
            return "Error: missing parameters";
        }
        try {
            ZoneId from = ZoneId.of(fromZone);
            ZoneId to = ZoneId.of(toZone);
            ZonedDateTime source = parse(datetime, from);
            ZonedDateTime target = source.withZoneSameInstant(to);
            return "Result: " + target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (" + to + ")";
        } catch (Exception e) {
            return "Timezone conversion failed: " + e.getMessage();
        }
    }

    private ZonedDateTime parse(String datetime, ZoneId zoneId) {
        String v = datetime.trim();
        if (v.length() == 10) {
            return LocalDate.parse(v).atStartOfDay(zoneId);
        }
        if (v.length() == 16) {
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zoneId);
        }
        if (v.length() == 19 && v.charAt(10) == ' ') {
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(zoneId);
        }
        return ZonedDateTime.parse(v);
    }
}
