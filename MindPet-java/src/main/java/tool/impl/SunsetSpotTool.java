package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import config.TencentMapConfig;
import config.WeatherConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import util.HttpJsonUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class SunsetSpotTool {

    private static final String GEOCODING_URL = "https://apis.map.qq.com/ws/geocoder/v1/";
    private static final String PLACE_SEARCH_URL = "https://apis.map.qq.com/ws/place/v1/search";
    private static final int RADIUS = 20000; // 20km
    private static final String[] SPOT_KEYWORDS = {
        "公园", "山顶", "观景台", "湖边", "江边", "高层露台"
    };

    private final TencentMapConfig mapConfig;
    private final WeatherConfig weatherConfig;

    @Autowired
    public SunsetSpotTool(TencentMapConfig mapConfig, WeatherConfig weatherConfig) {
        this.mapConfig = mapConfig;
        this.weatherConfig = weatherConfig;
    }

    @Tool(description = "Find the best spots nearby for watching sunrise (朝霞) or sunset (晚霞). Checks weather (cloud cover, humidity, sunrise/sunset time) and ranks scenic locations within 20km.")
    public String sunSkySpots(
            @ToolParam(description = "Location to search near (e.g. \"杭州西湖区\", \"北京朝阳区\")") String location,
            @ToolParam(description = "sunset(晚霞, default) or sunrise(朝霞)") String type) {

        if (location == null || location.isEmpty()) {
            return "请告诉我你在哪个城市或地点（如\"杭州西湖区\"）";
        }
        boolean isSunrise = "sunrise".equalsIgnoreCase(type) || "朝霞".equals(type);
        String label = isSunrise ? "朝霞" : "晚霞";
        String icon = isSunrise ? "🌄" : "🌅";

        try {
            // 1. Geocode
            double[] coords = geocode(location);
            if (coords == null) return "未找到位置: " + location;

            // 2. Weather
            JsonNode weather = getWeather(coords[0], coords[1]);
            JsonNode current = weather.path("current_condition").path(0);
            int clouds = current.path("cloudcover").asInt(100);
            int humidity = current.path("humidity").asInt(100);
            String field = isSunrise ? "sunrise" : "sunset";
            String skyTime = weather.path("weather").path(0).path("astronomy").path(0)
                .path(field).asText("unknown");

            // 3. Weather score
            int weatherScore = calcWeatherScore(clouds, humidity);
            String weatherLevel = weatherScore >= 80 ? "极佳 " + icon :
                weatherScore >= 60 ? "良好 ☀️" : weatherScore >= 40 ? "一般 ⛅" : "较差 ☁️";

            // 4. Search spots
            List<Spot> allSpots = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String kw : SPOT_KEYWORDS) {
                for (Spot s : searchNearby(coords[0], coords[1], kw)) {
                    if (seen.add(s.name + s.address)) allSpots.add(s);
                }
            }

            // 5. Sort by rating desc, then by distance asc
            allSpots.sort((a, b) -> {
                int r = Double.compare(b.rating, a.rating);
                return r != 0 ? r : Integer.compare(a.distance, b.distance);
            });

            // 6. Format
            return formatResult(allSpots, location, weatherScore, weatherLevel,
                skyTime, clouds, humidity, label, icon);

        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    private int calcWeatherScore(int clouds, int humidity) {
        int cloudScore = Math.max(0, 100 - clouds * 2); // fewer clouds = better
        int humidityScore = Math.max(0, 100 - Math.abs(humidity - 40) * 2); // ~40% ideal
        return (cloudScore + humidityScore) / 2;
    }

    private double[] geocode(String address) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl(GEOCODING_URL)
            .queryParam("address", address)
            .queryParam("key", mapConfig.getApiKey())
            .build().toUriString();
        JsonNode root = HttpJsonUtil.getJson(url);
        if (root.path("status").asInt() != 0) return null;
        JsonNode loc = root.path("result").path("location");
        double lat = loc.path("lat").asDouble();
        double lng = loc.path("lng").asDouble();
        if (lat == 0 && lng == 0) return null;
        return new double[]{lat, lng};
    }

    private JsonNode getWeather(double lat, double lng) throws Exception {
        String baseUrl = weatherConfig.getApi().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("openweathermap.org")) {
            baseUrl = "https://wttr.in/";
        }
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String language = weatherConfig.getApi().getLang();
        if (language == null || language.isBlank()) language = "zh";
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + lat + "," + lng)
            .queryParam("format", "j1")
            .queryParam("lang", language)
            .build().toUriString();
        return HttpJsonUtil.getJson(url);
    }

    private List<Spot> searchNearby(double lat, double lng, String keyword) throws Exception {
        List<Spot> spots = new ArrayList<>();
        String boundary = "nearby(" + lat + "," + lng + "," + RADIUS + ")";
        String url = UriComponentsBuilder.fromHttpUrl(PLACE_SEARCH_URL)
            .queryParam("boundary", boundary)
            .queryParam("keyword", keyword)
            .queryParam("page_size", 3)
            .queryParam("key", mapConfig.getApiKey())
            .build().toUriString();
        JsonNode root = HttpJsonUtil.getJson(url);
        if (root.path("status").asInt() != 0) return spots;

        JsonNode data = root.path("data");
        if (!data.isArray()) return spots;

        for (JsonNode item : data) {
            String name = item.path("title").asText("");
            String address = item.path("address").asText("");
            double rating = item.path("_distance").isMissingNode() ? 3.0 : 5.0;
            int distance = item.path("_distance").asInt(0);
            if (name.isEmpty()) continue;
            spots.add(new Spot(name, address, rating, distance));
        }
        return spots;
    }

    private String formatResult(List<Spot> spots, String location,
                                 int weatherScore, String weatherLevel,
                                 String skyTime, int clouds, int humidity,
                                 String label, String icon) {
        String timeLabel = icon.startsWith("🌄") ? "日出" : "日落";
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(label).append("观赏推荐】").append(location).append(" 20km内\n");
        sb.append("=".repeat(40)).append("\n");
        sb.append(timeLabel).append("时间: ").append(skyTime)
            .append(" | 云量: ").append(clouds).append("%")
            .append(" | 湿度: ").append(humidity).append("%\n");
        sb.append(label).append("天气评分: ").append(weatherScore).append("/100 ").append(weatherLevel).append("\n");
        sb.append("=".repeat(40)).append("\n");

        if (spots.isEmpty()) {
            sb.append("附近未找到合适的观景点。\n");
        } else {
            int max = Math.min(spots.size(), 15);
            for (int i = 0; i < max; i++) {
                Spot s = spots.get(i);
                sb.append(i + 1).append(". ").append(s.name).append("\n");
                sb.append("   ").append(s.address);
                if (s.distance > 0) sb.append(" | ").append(formatDist(s.distance));
                sb.append("\n");
                if (s.rating >= 4.0) sb.append("   ⭐ 高评分推荐\n");
                sb.append("\n");
            }
        }

        sb.append("💡 提示: 建议").append(timeLabel).append("前30分钟到达，").append(skyTime)
            .append("前后是最佳观赏时刻。");
        return sb.toString();
    }

    private String formatDist(int meters) {
        if (meters >= 1000) return String.format("%.1f km", meters / 1000.0);
        return meters + " m";
    }

    private static class Spot {
        String name, address;
        double rating;
        int distance;
        Spot(String n, String a, double r, int d) { name=n; address=a; rating=r; distance=d; }
    }
}
