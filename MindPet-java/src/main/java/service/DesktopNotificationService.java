package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable local outbox consumed by the AgentPet Electron main process.
 * Events remain pending until Electron acknowledges them.
 */
@Service
public class DesktopNotificationService implements UserNotificationPort {

    private static final int MAX_PENDING_PER_USER = 100;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final long RETENTION_MILLIS = Duration.ofDays(7).toMillis();

    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path storageFile = Path.of("desktop_notifications.json").toAbsolutePath().normalize();
    private final Map<String, LinkedHashMap<String, DesktopNotification>> pendingByUser = new LinkedHashMap<>();

    public DesktopNotificationService(Logger logger) {
        this.logger = logger;
    }

    @PostConstruct
    public synchronized void load() {
        if (!Files.isRegularFile(storageFile)) return;
        try {
            DesktopNotification[] notifications = mapper.readValue(storageFile.toFile(), DesktopNotification[].class);
            if (notifications != null) {
                for (DesktopNotification notification : notifications) {
                    if (!isValid(notification)) continue;
                    pendingByUser
                        .computeIfAbsent(notification.userId(), ignored -> new LinkedHashMap<>())
                        .put(notification.id(), notification);
                }
            }
            pruneExpiredLocked(System.currentTimeMillis());
        } catch (Exception e) {
            logger.log("WARN", "Unable to load desktop notifications: " + e.getMessage());
        }
    }

    @Override
    public synchronized void sendText(String userId, String title, String body) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isEmpty()) {
            logger.log("WARN", "Dropped desktop notification without a user ID");
            return;
        }

        DesktopNotification notification = new DesktopNotification(
            UUID.randomUUID().toString(),
            normalizedUserId,
            normalizeText(title, "AgentPet"),
            normalizeText(body, "You have a new notification."),
            "message",
            System.currentTimeMillis()
        );
        pendingByUser
            .computeIfAbsent(normalizedUserId, ignored -> new LinkedHashMap<>())
            .put(notification.id(), notification);
        pruneExpiredLocked(System.currentTimeMillis());
        persistLocked();
    }

    public synchronized List<DesktopNotification> poll(String userId, int limit) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isEmpty()) return List.of();

        pruneExpiredLocked(System.currentTimeMillis());
        LinkedHashMap<String, DesktopNotification> pending = pendingByUser.get(normalizedUserId);
        if (pending == null || pending.isEmpty()) return List.of();

        int boundedLimit = Math.max(1, Math.min(limit, MAX_PENDING_PER_USER));
        return pending.values().stream().limit(boundedLimit).toList();
    }

    public synchronized boolean acknowledge(String userId, String notificationId) {
        String normalizedUserId = normalize(userId);
        String normalizedNotificationId = normalize(notificationId);
        if (normalizedUserId.isEmpty() || normalizedNotificationId.isEmpty()) return false;

        LinkedHashMap<String, DesktopNotification> pending = pendingByUser.get(normalizedUserId);
        if (pending == null || pending.remove(normalizedNotificationId) == null) return false;
        if (pending.isEmpty()) pendingByUser.remove(normalizedUserId);
        persistLocked();
        return true;
    }

    private void pruneExpiredLocked(long now) {
        boolean changed = false;
        Iterator<Map.Entry<String, LinkedHashMap<String, DesktopNotification>>> users = pendingByUser.entrySet().iterator();
        while (users.hasNext()) {
            LinkedHashMap<String, DesktopNotification> pending = users.next().getValue();
            Iterator<DesktopNotification> iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                DesktopNotification notification = iterator.next();
                if (notification.createdAt() < now - RETENTION_MILLIS) {
                    iterator.remove();
                    changed = true;
                }
            }
            while (pending.size() > MAX_PENDING_PER_USER) {
                Iterator<String> ids = pending.keySet().iterator();
                ids.next();
                ids.remove();
                changed = true;
            }
            if (pending.isEmpty()) {
                users.remove();
                changed = true;
            }
        }
        if (changed) persistLocked();
    }

    private void persistLocked() {
        List<DesktopNotification> notifications = new ArrayList<>();
        for (LinkedHashMap<String, DesktopNotification> pending : pendingByUser.values()) {
            notifications.addAll(pending.values());
        }

        Path parent = storageFile.getParent();
        Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            mapper.writeValue(temporary.toFile(), notifications);
            try {
                Files.move(temporary, storageFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log("ERROR", "Unable to persist desktop notifications: " + e.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next successful write replaces this temporary file.
            }
        }
    }

    private boolean isValid(DesktopNotification notification) {
        return notification != null
            && !normalize(notification.id()).isEmpty()
            && !normalize(notification.userId()).isEmpty()
            && notification.createdAt() > 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeText(String value, String fallback) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) normalized = fallback;
        return normalized.length() <= MAX_TEXT_LENGTH ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
    }

    public record DesktopNotification(
        String id,
        String userId,
        String title,
        String body,
        String category,
        long createdAt
    ) {}
}
