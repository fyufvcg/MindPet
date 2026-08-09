package tool.impl;

import model.WeatherResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.WeatherService;

@Component
public class GetWeatherTool {

    private final WeatherService weatherService;

    @Autowired
    public GetWeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(description = "查询指定城市的实时天气信息")
    public String getWeather(
            @ToolParam(description = "城市名称，如北京、杭州、上海") String city) {
        if (city == null || city.isEmpty()) {
            return "错误：未指定城市";
        }
        WeatherResponse response = weatherService.getWeather(city);
        if (response.hasError()) {
            return "错误：" + response.getErrorMessage();
        }
        return formatWeather(response);
    }

    private String formatWeather(WeatherResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("天气查询结果\n");
        sb.append("=".repeat(30)).append("\n");
        sb.append("城市: ").append(response.getCityName()).append(", ").append(response.getCountryCode()).append("\n");
        sb.append("天气: ").append(response.getWeatherDescription()).append("\n");
        sb.append(String.format("温度: %.1f°C (体感: %.1f°C)\n", response.getTemperature(), response.getFeelsLike()));
        sb.append("湿度: ").append(response.getHumidity()).append("%\n");
        sb.append(String.format("风速: %.1f m/s\n", response.getWindSpeed()));
        sb.append("查询时间: ").append(response.getQueryTimeFormatted());
        return sb.toString();
    }
}
