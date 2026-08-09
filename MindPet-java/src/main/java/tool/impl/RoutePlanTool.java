package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import config.TencentMapConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import util.HttpJsonUtil;

import java.util.ArrayList;
import java.util.List;

@Component
public class RoutePlanTool {

    private static final String GEOCODING_URL = "https://apis.map.qq.com/ws/geocoder/v1/";
    private static final String PLACE_SEARCH_URL = "https://apis.map.qq.com/ws/place/v1/search/";
    private static final String DIRECTION_BASE = "https://apis.map.qq.com/ws/direction/v1/";

    private final TencentMapConfig config;
    private final IpGeoTool ipGeoTool;

    @Autowired
    public RoutePlanTool(TencentMapConfig config, IpGeoTool ipGeoTool) {
        this.config = config;
        this.ipGeoTool = ipGeoTool;
    }

    @Tool(description = "Plan a route from start to destination. Supports driving, walking, transit, bicycling. When you have exact coordinates (e.g. from didi_searchPlace), pass them via startLat/startLon/endLat/endLon to avoid ambiguous place name resolution.")
    public String routePlanning(
            @ToolParam(description = "Starting place name. Leave empty to auto-detect from IP. Ignored if startLat+startLon provided.") String start,
            @ToolParam(description = "Destination place name. Ignored if endLat+endLon provided.") String end,
            @ToolParam(description = "Travel mode: driving (驾车), walking (步行), transit (公交/地铁, default), bicycling (骑行).") String mode,
            @ToolParam(description = "Start latitude (optional, from didi_searchPlace). Must provide startLon too.") Double startLat,
            @ToolParam(description = "Start longitude (optional). Must provide startLat too.") Double startLon,
            @ToolParam(description = "End latitude (optional, from didi_searchPlace). Must provide endLon too.") Double endLat,
            @ToolParam(description = "End longitude (optional). Must provide endLat too.") Double endLon) {

        if (end == null && (endLat == null || endLon == null)) {
            return "Error: destination (end or endLat+endLon) is required.";
        }

        if (mode == null || mode.isEmpty()) {
            mode = "transit";
        }

        try {
            // Use coordinates directly if provided
            LocationResult from;
            if (startLat != null && startLon != null) {
                from = new LocationResult(startLat, startLon, start != null ? start : "起点");
            } else if (start != null && !start.isEmpty()) {
                from = resolveLocation(start);
            } else {
                String autoDetect = detectLocationFromIP();
                from = autoDetect != null ? resolveLocation(autoDetect) : null;
            }
            if (from == null) {
                String candidates = searchPlaceCandidates(start != null ? start : "");
                if (candidates != null) {
                    return "Could not pinpoint \"" + start + "\". Did you mean:\n" + candidates;
                }
                return "Could not find start location. Please provide a more specific name or use startLat/startLon.";
            }

            LocationResult to;
            if (endLat != null && endLon != null) {
                to = new LocationResult(endLat, endLon, end != null ? end : "目的地");
            } else {
                to = resolveLocation(end);
            }
            if (to == null) {
                String candidates = searchPlaceCandidates(end);
                if (candidates != null) {
                    return "Could not pinpoint \"" + end + "\". Did you mean:\n" + candidates;
                }
                return "Could not find \"" + end + "\". Try a simpler term or use endLat/endLon with coordinates from didi_searchPlace.";
            }

            // Get directions
            String directionUrl = UriComponentsBuilder.fromHttpUrl(DIRECTION_BASE + mode + "/")
                .queryParam("from", from.lat + "," + from.lng)
                .queryParam("to", to.lat + "," + to.lng)
                .queryParam("key", config.getApiKey())
                .build()
                .toUriString();

            JsonNode dirResult = HttpJsonUtil.getJson(directionUrl);
            if (dirResult.path("status").asInt() != 0) {
                return "Route planning failed: " + dirResult.path("message").asText("unknown error");
            }

            return formatRoute(dirResult, from.resolvedName, to.resolvedName, mode);
        } catch (Exception e) {
            return "Route planning failed: " + e.getMessage();
        }
    }

    /**
     * Resolve a place name to coordinates.
     * Tries geocoding first, then place search with progressive keyword shortening.
     * Returns null if both fail.
     */
    private LocationResult resolveLocation(String place) throws Exception {
        // 1. Try geocoding (works best for street addresses)
        LocationResult result = geocode(place);
        if (result != null) {
            return result;
        }

        // 2. Place search: try full keyword, then progressively shorter variants
        return searchPlaceProgressive(place);
    }

    /**
     * Search for candidates to show the LLM when an exact match fails.
     * Returns formatted candidate list or null.
     */
    private String searchPlaceCandidates(String keyword) {
        try {
            String[] variants = shortenKeyword(keyword);
            for (String variant : variants) {
                String url = UriComponentsBuilder.fromHttpUrl(PLACE_SEARCH_URL)
                    .queryParam("keyword", variant)
                    .queryParam("page_size", 5)
                    .queryParam("key", config.getApiKey())
                    .build()
                    .toUriString();
                JsonNode root = HttpJsonUtil.getJson(url);
                if (root.path("status").asInt() != 0) continue;
                JsonNode data = root.path("data");
                if (!data.isArray() || data.isEmpty()) continue;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(5, data.size()); i++) {
                    JsonNode item = data.get(i);
                    String title = item.path("title").asText("");
                    String address = item.path("address").asText("");
                    if (!title.isEmpty()) {
                        sb.append("- ").append(title);
                        if (!address.isEmpty()) sb.append(" (").append(address).append(")");
                        sb.append("\n");
                    }
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Place search with progressive keyword shortening.
     */
    private LocationResult searchPlaceProgressive(String keyword) throws Exception {
        String[] variants = shortenKeyword(keyword);
        for (String variant : variants) {
            LocationResult result = searchPlace(variant);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Generate progressively shorter keyword variants.
     * "杭州阿里巴巴高桥云港园区" → "杭州阿里巴巴高桥云港园区", "阿里巴巴高桥云港园区",
     * "阿里巴巴高桥云港", "高桥云港园区", "高桥云港"
     */
    private String[] shortenKeyword(String keyword) {
        if (keyword.length() <= 4) return new String[]{keyword};

        List<String> variants = new ArrayList<>();
        variants.add(keyword);

        // Remove known city prefixes
        for (String prefix : new String[]{"杭州", "北京", "上海", "广州", "深圳", "成都", "武汉", "南京", "西安", "重庆"}) {
            if (keyword.startsWith(prefix) && keyword.length() > prefix.length() + 2) {
                String withoutCity = keyword.substring(prefix.length()).trim();
                if (!variants.contains(withoutCity)) variants.add(withoutCity);
                break;
            }
        }

        // Split by common delimiters, try segments
        String[] parts = keyword.split("[，,、\\s]+");
        if (parts.length >= 2) {
            String last = parts[parts.length - 1].trim();
            if (last.length() >= 2 && !variants.contains(last)) variants.add(last);
        }

        // Progressive truncation from the right
        String current = keyword;
        while (current.length() > 4) {
            // Remove trailing 2-3 chars at a time
            int cut = Math.min(3, current.length() - 2);
            current = current.substring(0, current.length() - cut).trim();
            if (current.length() >= 2 && !variants.contains(current)) {
                variants.add(current);
            }
        }

        return variants.toArray(new String[0]);
    }

    /**
     * Geocode an address to coordinates using Tencent Maps geocoding API.
     * Returns null if the address cannot be resolved.
     */
    private LocationResult geocode(String address) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl(GEOCODING_URL)
            .queryParam("address", address)
            .queryParam("key", config.getApiKey())
            .build()
            .toUriString();
        JsonNode root = HttpJsonUtil.getJson(url);
        if (root.path("status").asInt() != 0) {
            return null;
        }
        JsonNode location = root.path("result").path("location");
        double lat = location.path("lat").asDouble();
        double lng = location.path("lng").asDouble();
        if (lat == 0 && lng == 0) {
            return null;
        }
        String title = root.path("result").path("title").asText(address);
        return new LocationResult(lat, lng, title);
    }

    /**
     * Search for a place by name using Tencent Maps place search API.
     * Returns the top matching result, or null if nothing found.
     */
    private LocationResult searchPlace(String keyword) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl(PLACE_SEARCH_URL)
            .queryParam("keyword", keyword)
            .queryParam("page_size", 1)
            .queryParam("key", config.getApiKey())
            .build()
            .toUriString();
        JsonNode root = HttpJsonUtil.getJson(url);
        if (root.path("status").asInt() != 0) {
            return null;
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return null;
        }
        JsonNode first = data.get(0);
        JsonNode location = first.path("location");
        double lat = location.path("lat").asDouble();
        double lng = location.path("lng").asDouble();
        if (lat == 0 && lng == 0) {
            return null;
        }
        String title = first.path("title").asText(keyword);
        return new LocationResult(lat, lng, title);
    }

    /**
     * Auto-detect the user's location from IP using ip-api.com.
     * Returns "city, region, country" or just coordinates string, or null on failure.
     */
    private String detectLocationFromIP() {
        JsonNode root = ipGeoTool.getLocationData();
        if (root == null || !"success".equals(root.path("status").asText())) return null;
        String city = root.path("city").asText("");
        String region = root.path("regionName").asText("");
        String country = root.path("country").asText("");
        double lat = root.path("lat").asDouble();
        double lon = root.path("lon").asDouble();
        StringBuilder loc = new StringBuilder();
        if (!country.isEmpty()) loc.append(country);
        if (!region.isEmpty()) { if (loc.length() > 0) loc.append(" "); loc.append(region); }
        if (!city.isEmpty()) { if (loc.length() > 0) loc.append(" "); loc.append(city); }
        if (loc.length() == 0) loc.append(lat).append(",").append(lon);
        return loc.toString();
    }

    private String formatRoute(JsonNode dirResult, String start, String end, String mode) {
        JsonNode result = dirResult.path("result");
        JsonNode routes = result.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return "No route found from " + start + " to " + end + ".";
        }

        JsonNode bestRoute = routes.get(0);
        int distance = bestRoute.path("distance").asInt();
        int duration = bestRoute.path("duration").asInt();

        String modeName = switch (mode) {
            case "driving" -> "驾车";
            case "walking" -> "步行";
            case "bicycling" -> "骑行";
            case "transit" -> "公交/地铁";
            default -> mode;
        };

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(modeName).append("路线】").append(start).append(" → ").append(end).append("\n");
        sb.append("总距离: ").append(formatDistance(distance)).append("\n");
        sb.append("预计时间: ").append(formatDuration(duration)).append("\n");
        sb.append("=".repeat(30)).append("\n");

        JsonNode steps = bestRoute.path("steps");
        if (steps.isArray()) {
            int stepNum = 1;
            for (JsonNode step : steps) {
                String instruction = step.path("instruction").asText("");
                int stepDist = step.path("distance").asInt();
                if (!instruction.isEmpty()) {
                    sb.append(stepNum).append(". ").append(instruction);
                    if (stepDist > 0) {
                        sb.append(" (").append(formatDistance(stepDist)).append(")");
                    }
                    sb.append("\n");
                    stepNum++;
                }
            }
        }

        return sb.toString().trim();
    }

    private String formatDistance(int meters) {
        if (meters >= 1000) {
            return String.format("%.1f km", meters / 1000.0);
        }
        return meters + " m";
    }

    private String formatDuration(int minutes) {
        if (minutes >= 60) {
            int hours = minutes / 60;
            int mins = minutes % 60;
            return mins > 0 ? hours + " 小时 " + mins + " 分钟" : hours + " 小时";
        }
        return minutes + " 分钟";
    }

    // ==================== Inner class ====================

    private static class LocationResult {
        final double lat;
        final double lng;
        final String resolvedName;

        LocationResult(double lat, double lng, String resolvedName) {
            this.lat = lat;
            this.lng = lng;
            this.resolvedName = resolvedName;
        }
    }
}
