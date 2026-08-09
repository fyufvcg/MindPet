package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import util.HttpJsonUtil;

@Component
public class IpGeoTool {

    @Tool(description = "Lookup IP geolocation data. If no IP provided, detects the current device's location.")
    public String ipGeolocation(
            @ToolParam(description = "IP address to look up. Leave empty to auto-detect current location.") String ip) {
        try {
            // NOTE: ip-api.com free tier rejects the "fields" param, use full response
            String url;
            if (ip != null && !ip.isEmpty()) {
                url = "http://ip-api.com/json/" + ip + "?lang=en";
            } else {
                url = "http://ip-api.com/json/?lang=en";
            }

            JsonNode root = HttpJsonUtil.getJson(url);
            if (!"success".equals(root.path("status").asText())) {
                return "Lookup failed: " + root.path("message").asText("unknown");
            }
            return "IP: " + root.path("query").asText(ip != null ? ip : "auto-detected")
                + "\nLocation: " + root.path("country").asText("") + " " + root.path("regionName").asText("") + " " + root.path("city").asText("")
                + "\nZip: " + root.path("zip").asText("")
                + "\nCoords: " + root.path("lat").asDouble() + ", " + root.path("lon").asDouble()
                + "\nISP: " + root.path("isp").asText("");
        } catch (Exception e) {
            return "IP lookup failed: " + e.getMessage();
        }
    }

    /**
     * Get raw IP location data as JsonNode. Shared by RoutePlanTool and TicketQueryTool.
     * Returns null on failure.
     */
    public JsonNode getLocationData() {
        try {
            return HttpJsonUtil.getJson("http://ip-api.com/json/?lang=en");
        } catch (Exception e) {
            return null;
        }
    }
}
