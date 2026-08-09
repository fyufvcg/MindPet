package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.SnapTicketService;
import service.SnapTicketService.SnapTask;
import tool.ToolUserContext;

import java.time.LocalDate;
import java.time.LocalTime;

@Component
public class SnapTicketTool {

    private final SnapTicketService snapService;

    @Autowired
    public SnapTicketTool(SnapTicketService snapService) {
        this.snapService = snapService;
    }

    @Tool(description = "Schedule a train ticket snapping task. The system will scan every second starting 1 minute before the release time for 15 minutes. If a matching train is found, it will auto-submit the order. Requires 12306 account info to be saved first.")
    public String snapTicket(
            @ToolParam(description = "Departure station (e.g. \"杭州东\")") String from,
            @ToolParam(description = "Arrival station (e.g. \"北京南\")") String to,
            @ToolParam(description = "Travel date YYYY-MM-DD (must be 15+ days in future)") String targetDate,
            @ToolParam(description = "Train type: G(高铁), D(动车), K(快速), or 不限. Default 不限.") String trainType,
            @ToolParam(description = "Time window: \"08:00-12:00\". Default full day.") String timeWindow,
            @ToolParam(description = "Seat: 二等座, 一等座, 商务座, 软卧, 硬卧. Default 二等座.") String seat,
            @ToolParam(description = "Station ticket release time: \"yyyy-MM-dd HH:mm:ss\". Search via web_search first!") String releaseTime) {

        if (from == null || to == null || targetDate == null || releaseTime == null) {
            return "Missing info. Need: from, to, target_date, release_time.\n"
                + "release_time = the station's ticket release time (search it with web_search first).\n"
                + "Example: snap_ticket(from=\"杭州东\", to=\"北京南\", target_date=\"2026-08-08\", "
                + "train_type=\"G\", time_window=\"08:00-12:00\", seat=\"二等座\", release_time=\"2026-07-25 10:00:00\")";
        }
        if (trainType == null || trainType.isEmpty()) trainType = "不限";
        if (timeWindow == null || timeWindow.isEmpty()) timeWindow = "00:00-23:59";
        if (seat == null || seat.isEmpty()) seat = "二等座";

        // Validate target_date is >= 15 days in future (ticket release window)
        try {
            LocalDate target = LocalDate.parse(targetDate);
            if (target.isBefore(LocalDate.now().plusDays(14))) {
                return "车票预售期为15天，" + targetDate + " 不在预售期内。请选择15天后的日期。";
            }
        } catch (Exception e) {
            return "日期格式错误: " + targetDate + "，应为 YYYY-MM-DD";
        }

        // Validate time format
        try {
            LocalTime.parse(releaseTime.substring(11), java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (Exception e) {
            // Try HH:mm format
            try {
                LocalTime.parse(releaseTime.substring(11), java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception ex) {
                return "放票时间格式错误: " + releaseTime + "，应为 yyyy-MM-dd HH:mm:ss";
            }
        }

        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "Unable to create task: current user is unavailable.";
        }

        SnapTask task = new SnapTask(userId, from, to, targetDate, trainType, timeWindow, seat, releaseTime);
        return snapService.register(userId, task);
    }
}
