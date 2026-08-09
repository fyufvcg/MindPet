package tool.impl;

import model.WeatherResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.WeatherPushService;
import service.WeatherService;
import tool.ToolUserContext;

@Component
public class SubscribeWeatherTool {

    private final WeatherService weatherService;
    private final WeatherPushService pushService;

    @Autowired
    public SubscribeWeatherTool(WeatherService weatherService, WeatherPushService pushService) {
        this.weatherService = weatherService;
        this.pushService = pushService;
    }

    @Tool(description = "订阅天气推送，每天定时推送天气信息")
    public String subscribeWeather(
            @ToolParam(description = "城市名称") String city,
            @ToolParam(description = "推送时间（小时，0-23），默认7点") Integer hour) {

        int pushHour = 7;
        if (hour != null) {
            pushHour = hour;
        }
        if (city == null || city.isEmpty()) {
            return "错误：未指定城市";
        }
        WeatherResponse weather = weatherService.getWeather(city);
        if (weather.hasError()) {
            return "错误：未找到城市 " + city;
        }
        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "Unable to subscribe: current user is unavailable.";
        }
        pushService.subscribe(userId, weather.getCityName(), pushHour);
        return "已成功订阅 " + weather.getCityName() + " 的天气推送，每天 " + pushHour + " 点推送";
    }
}
