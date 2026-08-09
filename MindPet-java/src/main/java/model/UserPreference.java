package model;

/**
 * 用户天气推送偏好
 */
public class UserPreference {

    private String userId;
    private String city;
    private int pushHour;
    private boolean pushEnabled;
    private long lastPushTime;
    private long lastSevereAlertTime;

    public UserPreference() {}

    public UserPreference(String userId, String city, int pushHour) {
        this.userId = userId;
        this.city = city;
        this.pushHour = pushHour;
        this.pushEnabled = true;
        this.lastPushTime = 0;
        this.lastSevereAlertTime = 0;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public int getPushHour() { return pushHour; }
    public void setPushHour(int pushHour) { this.pushHour = pushHour; }

    public boolean isPushEnabled() { return pushEnabled; }
    public void setPushEnabled(boolean pushEnabled) { this.pushEnabled = pushEnabled; }

    public long getLastPushTime() { return lastPushTime; }
    public void setLastPushTime(long lastPushTime) { this.lastPushTime = lastPushTime; }

    public long getLastSevereAlertTime() { return lastSevereAlertTime; }
    public void setLastSevereAlertTime(long lastSevereAlertTime) { this.lastSevereAlertTime = lastSevereAlertTime; }

    public String toSaveString() {
        return userId + "|" + city + "|" + pushHour + "|" + pushEnabled + "|" + lastPushTime + "|" + lastSevereAlertTime;
    }

    public static UserPreference fromSaveString(String line) {
        String[] parts = line.split("\\|");
        if (parts.length < 5) return null;
        UserPreference pref = new UserPreference();
        pref.userId = parts[0];
        pref.city = parts[1];
        pref.pushHour = Integer.parseInt(parts[2]);
        pref.pushEnabled = Boolean.parseBoolean(parts[3]);
        pref.lastPushTime = Long.parseLong(parts[4]);
        if (parts.length >= 6) {
            pref.lastSevereAlertTime = Long.parseLong(parts[5]);
        }
        return pref;
    }
}
