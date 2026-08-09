package service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import model.UserPreference;
import model.WeatherResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.*;

@Service
public class WeatherPushService {

    private final WeatherService weatherService;
    private final AiService aiService;
    private final Logger logger;
    private final UserNotificationPort notificationPort;
    private final List<UserPreference> preferences = new CopyOnWriteArrayList<>();
    private final String prefsFile = "user_preferences.txt";
    private ScheduledExecutorService scheduler;

    @Autowired
    public WeatherPushService(WeatherService weatherService, @Lazy AiService aiService, Logger logger,
                              UserNotificationPort notificationPort) {
        this.weatherService = weatherService;
        this.aiService = aiService;
        this.logger = logger;
        this.notificationPort = notificationPort;
    }

    @PostConstruct
    public void init() {
        loadPreferences();
        start();
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newScheduledThreadPool(1);
        long initialDelay = getInitialDelay();
        scheduler.scheduleAtFixedRate(this::checkAndPush, initialDelay, 60 * 60 * 1000, TimeUnit.MILLISECONDS);
        logger.log("INFO", "天气推送服务已启动，下次检查: " + (initialDelay / 1000 / 60) + " 分钟后");
    }

    private long getInitialDelay() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
        return java.time.Duration.between(now, nextHour).toMillis();
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            logger.log("INFO", "天气推送服务已停止");
        }
    }

    public void subscribe(String userId, String city, int pushHour) {
        start();
        for (UserPreference pref : preferences) {
            if (pref.getUserId().equals(userId)) {
                pref.setCity(city);
                pref.setPushHour(pushHour);
                pref.setPushEnabled(true);
                savePreferences();
                return;
            }
        }
        UserPreference pref = new UserPreference(userId, city, pushHour);
        preferences.add(pref);
        savePreferences();
    }

    public void unsubscribe(String userId) {
        preferences.removeIf(p -> p.getUserId().equals(userId));
        savePreferences();
    }

    public boolean isSubscribed(String userId) {
        return preferences.stream().anyMatch(p -> p.getUserId().equals(userId) && p.isPushEnabled());
    }

    public UserPreference getPreference(String userId) {
        return preferences.stream().filter(p -> p.getUserId().equals(userId)).findFirst().orElse(null);
    }

    private void checkAndPush() {
        LocalTime now = LocalTime.now();
        int currentHour = now.getHour();
        String today = java.time.LocalDate.now().toString();

        for (UserPreference pref : preferences) {
            if (!pref.isPushEnabled()) continue;
            try {
                WeatherResponse weather = weatherService.getWeather(pref.getCity());
                if (weather.hasError()) continue;

                boolean isSubscribedHour = (pref.getPushHour() == currentHour);
                boolean isSevere = shouldPush(weather);

                if (isSevere) {
                    String lastAlertDate = formatDate(pref.getLastSevereAlertTime());
                    if (today.equals(lastAlertDate)) continue;
                    String message = buildPushMessage(pref, weather, true);
                    notificationPort.sendText(pref.getUserId(), "恶劣天气提醒", message);
                    pref.setLastSevereAlertTime(System.currentTimeMillis());
                    savePreferences();
                } else if (isSubscribedHour) {
                    String lastPushDate = formatDate(pref.getLastPushTime());
                    if (today.equals(lastPushDate)) continue;
                    String message = buildPushMessage(pref, weather, false);
                    notificationPort.sendText(pref.getUserId(), "天气播报", message);
                    pref.setLastPushTime(System.currentTimeMillis());
                    savePreferences();
                }
            } catch (Exception e) {
                logger.log("ERROR", "推送天气失败: " + e.getMessage());
            }
        }
    }

    private String formatDate(long timestamp) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(timestamp));
    }

    private boolean shouldPush(WeatherResponse weather) {
        String desc = weather.getWeatherDescription();
        double temp = weather.getTemperature();
        if (desc != null && (desc.contains("雨") || desc.contains("雪") || desc.contains("雾") || desc.contains("暴风") || desc.contains("雷"))) return true;
        if (temp > 35 || temp < 0) return true;
        if (weather.getWindSpeed() > 10) return true;
        return false;
    }

    private String buildPushMessage(UserPreference pref, WeatherResponse weather, boolean isSevereAlert) {
        if (!aiService.isConfigured()) return buildFallbackMessage(weather, isSevereAlert);
        try {
            String weatherData = String.format("城市: %s\n天气: %s\n温度: %.1f°C\n湿度: %d%%\n风速: %.1f m/s",
                weather.getCityName(), weather.getWeatherDescription(), weather.getTemperature(), weather.getHumidity(), weather.getWindSpeed());
            String prompt = isSevereAlert
                ? "你是 MindPet。现在" + pref.getCity() + "突发恶劣天气，天气数据：\n" + weatherData + "\n请写一条紧急提醒，不超过3句话。"
                : "你是 MindPet。每日天气推送，天气数据：\n" + weatherData + "\n请写一条天气播报，不超过3句话。";
            String reply = aiService.chatSimple(prompt);
            if (reply != null && !reply.trim().isEmpty()) {
                return isSevereAlert ? "⚠️ 恶劣天气提醒\n" + reply : reply;
            }
        } catch (Exception e) {
            logger.log("WARNING", "LLM 生成推送消息失败: " + e.getMessage());
        }
        return buildFallbackMessage(weather, isSevereAlert);
    }

    private String buildFallbackMessage(WeatherResponse weather, boolean isSevereAlert) {
        String prefix = isSevereAlert ? "恶劣天气提醒" : "天气播报";
        return prefix + "\n" + weather.getCityName() + ": " + weather.getWeatherDescription() + "\n" + String.format("温度: %.1f°C", weather.getTemperature());
    }

    private void loadPreferences() {
        File file = new File(prefsFile);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                UserPreference pref = UserPreference.fromSaveString(line);
                if (pref != null) preferences.add(pref);
            }
            logger.log("INFO", "已加载 " + preferences.size() + " 个用户订阅");
        } catch (IOException e) {
            logger.log("ERROR", "加载用户偏好失败: " + e.getMessage());
        }
    }

    private synchronized void savePreferences() {
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(prefsFile), StandardCharsets.UTF_8))) {
            for (UserPreference pref : preferences) {
                writer.println(pref.toSaveString());
            }
        } catch (IOException e) {
            logger.log("ERROR", "保存用户偏好失败: " + e.getMessage());
        }
    }
}
