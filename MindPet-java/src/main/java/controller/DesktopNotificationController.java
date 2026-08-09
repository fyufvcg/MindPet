package controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import service.DesktopNotificationService;

import java.util.List;
import java.util.Map;

/** Delivery API consumed by the local Electron main process. */
@RestController
@RequestMapping("/api/desktop/notifications")
public class DesktopNotificationController {

    private final DesktopNotificationService notificationService;

    public DesktopNotificationController(DesktopNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public Map<String, Object> poll(
            @RequestParam String userId,
            @RequestParam(defaultValue = "20") int limit) {
        List<DesktopNotificationService.DesktopNotification> notifications = notificationService.poll(userId, limit);
        return Map.of("status", "ok", "notifications", notifications);
    }

    @PostMapping("/{notificationId}/ack")
    public Map<String, Object> acknowledge(
            @PathVariable String notificationId,
            @RequestParam String userId) {
        boolean acknowledged = notificationService.acknowledge(userId, notificationId);
        return Map.of("status", acknowledged ? "ok" : "not_found", "acknowledged", acknowledged);
    }
}
