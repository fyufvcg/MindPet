package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.WeatherPushService;
import tool.ToolUserContext;

@Component
public class UnsubscribeWeatherTool {

    private final WeatherPushService pushService;

    @Autowired
    public UnsubscribeWeatherTool(WeatherPushService pushService) {
        this.pushService = pushService;
    }

    @Tool(description = "取消天气推送订阅")
    public String unsubscribeWeather() {
        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "Unable to unsubscribe: current user is unavailable.";
        }
        if (!pushService.isSubscribed(userId)) {
            return "你还没有订阅天气推送";
        }
        pushService.unsubscribe(userId);
        return "已取消天气推送订阅";
    }
}
