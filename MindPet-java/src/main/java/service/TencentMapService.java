package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.TencentMapConfig;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 腾讯地图 WebService API — 地点搜索、地址解析。
 * 用于替代滴滴 maps_textsearch（滴滴搜索不准）。
 */
@Service
public class TencentMapService {

    private static final String SUGGESTION_URL = "https://apis.map.qq.com/ws/place/v1/suggestion";

    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public TencentMapService(TencentMapConfig config) {
        this.apiKey = config.getApiKey();
    }

    /**
     * 搜索地点，返回 {display_name, city, lng, lat} 列表。
     */
    public String search(String keyword) {
        try {
            String url = SUGGESTION_URL + "?keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&key=" + apiKey
                + "&page_size=5";
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "腾讯地图搜索失败: HTTP " + resp.statusCode();

            JsonNode root = mapper.readTree(resp.body());
            if (root.path("status").asInt() != 0) {
                return "腾讯地图搜索失败: " + root.path("message").asText("未知错误");
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) return "未找到「" + keyword + "」相关地点";

            StringBuilder sb = new StringBuilder("找到 " + Math.min(data.size(), 5) + " 个地点:\n");
            for (int i = 0; i < Math.min(data.size(), 5); i++) {
                JsonNode item = data.get(i);
                String title = item.path("title").asText("");
                String address = item.path("address").asText("");
                String city = item.path("city").asText("");
                String district = item.path("district").asText("");
                JsonNode loc = item.path("location");
                String lng = loc.path("lng").asText("");
                String lat = loc.path("lat").asText("");
                sb.append(i + 1).append(". ").append(title);
                if (!address.isBlank()) sb.append(" | ").append(address);
                if (!city.isBlank()) sb.append(" | ").append(city);
                if (!district.isBlank()) sb.append(" ").append(district);
                if (!lng.isBlank()) sb.append(" | (").append(lng).append(",").append(lat).append(")");
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "腾讯地图搜索异常: " + e.getMessage();
        }
    }
}
