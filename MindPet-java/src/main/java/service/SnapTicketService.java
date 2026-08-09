package service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import util.Logger;
import util.RailwayApiUtil;
import util.RailwayApiUtil.RailwayAccount;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SnapTicketService {

    private static final String TASKS_FILE = "snap_tasks.txt";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int SCAN_DURATION_MIN = 15;
    private static final int SCAN_INTERVAL_MS = 1000;
    private static final int START_EARLY_SEC = 60; // 提前1分钟

    private final Logger logger;
    private final UserNotificationPort notificationPort;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, SnapTask> activeTasks = new ConcurrentHashMap<>();

    @Autowired
    public SnapTicketService(Logger logger, UserNotificationPort notificationPort) {
        this.logger = logger;
        this.notificationPort = notificationPort;
    }

    @PostConstruct
    public void init() {
        loadTasks();
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    // ==================== Public API ====================

    /**
     * Register a snap task. Called by SnapTicketTool.
     */
    public String register(String userId, SnapTask task) {
        if (activeTasks.containsKey(task.id)) {
            return "已存在相同路线的抢票任务";
        }

        // Validate account
        RailwayAccount account;
        try {
            account = RailwayApiUtil.loadAccount();
        } catch (Exception e) {
            return "读取账户信息失败: " + e.getMessage();
        }
        if (account == null) return "请先发送12306账户信息 /account";

        activeTasks.put(task.id, task);
        saveTasks();
        scheduleTask(task);
        logger.log("INFO", "已注册抢票任务: " + task.id + " → " + task.from + "→" + task.to + " " + task.targetDate + " @" + task.releaseTime);
        return "已设置抢票 ✓\n" + task.from + "→" + task.to + " " + task.targetDate
            + "\n车次类型: " + (task.trainType != null ? task.trainType : "不限")
            + "\n时段: " + (task.timeWindow != null ? task.timeWindow : "全天")
            + "\n座位: " + (task.seat != null ? task.seat : "二等座")
            + "\n放票时间: " + task.releaseTime + "（提前1分钟开始扫描，持续15分钟）";
    }

    // ==================== Scheduling ====================

    private void scheduleTask(SnapTask task) {
        long delayMs = computeDelay(task.releaseTime);
        if (delayMs < 0) delayMs = 0;

        scheduler.schedule(() -> runScanLoop(task), delayMs, TimeUnit.MILLISECONDS);
        logger.log("INFO", "抢票任务已调度: " + task.id + " 延迟 " + (delayMs / 1000) + " 秒");
    }

    private long computeDelay(String releaseTime) {
        try {
            LocalDateTime release = LocalDateTime.parse(releaseTime, DTF);
            long delayMs = java.time.Duration.between(LocalDateTime.now(), release).toMillis() - START_EARLY_SEC * 1000;
            return Math.max(0, delayMs);
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== Scan Loop ====================

    private void runScanLoop(SnapTask task) {
        logger.log("INFO", "抢票扫描开始: " + task.id);
        notifyUser(task.userId, "开始抢" + task.targetDate + " " + task.from + "→" + task.to + " 的车票...");

        long deadline = System.currentTimeMillis() + SCAN_DURATION_MIN * 60_000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                RailwayAccount account = RailwayApiUtil.loadAccount();
                if (account == null || !RailwayApiUtil.validateCookie(account.cookie())) {
                    notifyUser(task.userId, "12306 cookie已过期，抢票任务暂停。请重新发送 /account");
                    activeTasks.remove(task.id);
                    saveTasks();
                    return;
                }

                String[] match = RailwayApiUtil.findMatchingTrain(
                    task.from, task.to, task.targetDate,
                    task.trainType, task.timeWindow, task.seat,
                    account.cookie()
                );

                if (match != null) {
                    // Found a matching train! Submit order.
                    logger.log("INFO", "发现匹配车次: " + match[1] + " " + match[2]);
                    String result = RailwayApiUtil.submitOrder(
                        task.from, task.to, task.targetDate,
                        match[1], task.seat, account
                    );

                    if (result.startsWith("SUCCESS")) {
                        notifyUser(task.userId, "抢到了！\n" + result);
                        activeTasks.remove(task.id);
                        saveTasks();
                        return;
                    } else if (result.contains("验证") || result.contains("验码")) {
                        logger.log("INFO", "被验证码拦截，继续扫描下一个车次");
                        // Continue — try next train in the next scan
                    } else {
                        logger.log("INFO", "下单失败: " + result + " 继续扫描...");
                    }
                }

                Thread.sleep(SCAN_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.log("ERROR", "扫描异常: " + e.getMessage());
                try { Thread.sleep(SCAN_INTERVAL_MS); } catch (InterruptedException ignored) {}
            }
        }

        // Timeout
        notifyUser(task.userId, "抢票超时，15分钟内未找到 " + task.from + "→" + task.to
            + " 的 " + task.trainType + " " + task.seat + "\n建议手动购买。");
        activeTasks.remove(task.id);
        saveTasks();
        logger.log("INFO", "抢票任务超时: " + task.id);
    }

    private void notifyUser(String userId, String message) {
        try {
            notificationPort.sendText(userId, "抢票提醒", message);
        } catch (Exception e) {
            logger.log("WARN", "Unable to queue ticket notification: " + e.getMessage());
        }
    }

    // ==================== Persistence ====================

    private synchronized void saveTasks() {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(TASKS_FILE), StandardCharsets.UTF_8))) {
            for (SnapTask t : activeTasks.values()) {
                w.println(t.toLine());
            }
        } catch (IOException e) {
            logger.log("ERROR", "保存抢票任务失败: " + e.getMessage());
        }
    }

    private void loadTasks() {
        File file = new File(TASKS_FILE);
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                SnapTask task = SnapTask.fromLine(line.trim());
                if (task != null) {
                    activeTasks.put(task.id, task);
                    scheduleTask(task);
                    logger.log("INFO", "恢复抢票任务: " + task.id);
                }
            }
        } catch (Exception e) {
            logger.log("ERROR", "加载抢票任务失败: " + e.getMessage());
        }
    }

    // ==================== Task Model ====================

    public static class SnapTask {
        public String id;          // userId:from:to:targetDate
        public String userId;
        public String from;
        public String to;
        public String targetDate;
        public String trainType;   // G/D/K/Z/不限
        public String timeWindow;  // 08:00-12:00
        public String seat;        // 二等座
        public String releaseTime; // yyyy-MM-dd HH:mm:ss

        public SnapTask() {}

        public SnapTask(String userId, String from, String to, String targetDate,
                        String trainType, String timeWindow, String seat, String releaseTime) {
            this.userId = userId;
            this.from = from;
            this.to = to;
            this.targetDate = targetDate;
            this.trainType = trainType != null ? trainType : "不限";
            this.timeWindow = timeWindow != null ? timeWindow : "00:00-23:59";
            this.seat = seat != null ? seat : "二等座";
            this.releaseTime = releaseTime;
            this.id = userId + ":" + from + ":" + to + ":" + targetDate;
        }

        String toLine() {
            return String.join("|", id, userId, from, to, targetDate, trainType, timeWindow, seat, releaseTime);
        }

        static SnapTask fromLine(String line) {
            if (line == null || line.isEmpty()) return null;
            String[] p = line.split("\\|");
            if (p.length < 9) return null;
            SnapTask t = new SnapTask();
            t.id = p[0]; t.userId = p[1]; t.from = p[2]; t.to = p[3];
            t.targetDate = p[4]; t.trainType = p[5]; t.timeWindow = p[6];
            t.seat = p[7]; t.releaseTime = p[8];
            return t;
        }
    }
}
