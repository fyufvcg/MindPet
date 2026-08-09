package tool.impl;

import model.UserPreference;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.WeatherPushService;
import tool.ToolUserContext;

@Component
public class ShowSubscriptionTool {

    private final WeatherPushService pushService;

    @Autowired
    public ShowSubscriptionTool(WeatherPushService pushService) {
        this.pushService = pushService;
    }

    @Tool(description = "查看当前的天气推送订阅信息")
    public String showSubscription() {
        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "Unable to view subscription: current user is unavailable.";
        }
        UserPreference pref = pushService.getPreference(userId);
        if (pref == null || !pref.isPushEnabled()) {
            return "你还没有订阅天气推送";
        }
        return "当前订阅：城市 " + pref.getCity() + "，每天 " + pref.getPushHour() + " 点推送";
    }
}
