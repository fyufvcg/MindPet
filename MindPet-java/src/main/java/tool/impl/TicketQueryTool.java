package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

@Component
public class TicketQueryTool {

    // mcp-server-12306 default address: http://localhost:8000/mcp
    private static final String MCP_URL = "http://localhost:8000/mcp";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IpGeoTool ipGeoTool;
    private String mcpSessionId = null;

    @Autowired
    public TicketQueryTool(IpGeoTool ipGeoTool) {
        this.ipGeoTool = ipGeoTool;
    }

    @Tool(description = "Query train/high-speed rail tickets between two stations. Returns train numbers, departure/arrival times, durations, seat classes, remaining seats and prices.")
    public String queryTickets(
            @ToolParam(description = "Departure station or city name (e.g. \"杭州东\"). Leave empty to auto-detect from your current location.") String from,
            @ToolParam(description = "Arrival station or city name (e.g. \"上海虹桥\", \"北京\")") String to,
            @ToolParam(description = "Travel date in YYYY-MM-DD format. Leave empty for today (auto-detected). Only provide if user explicitly says a future date.") String date) {

        if (to == null || to.isEmpty()) {
            return "Error: destination station (to) is required.";
        }

        if (date == null || date.isEmpty()) {
            date = LocalDate.now().toString();
        } else {
            // Validate: reject dates in the past or absurdly far future (>60 days)
            try {
                LocalDate parsed = LocalDate.parse(date);
                LocalDate today = LocalDate.now();
                if (parsed.isBefore(today) || parsed.isAfter(today.plusDays(60))) {
                    date = today.toString();
                }
            } catch (Exception e) {
                date = LocalDate.now().toString();
            }
        }

        // Auto-detect departure city from IP if not provided
        if (from == null || from.isEmpty()) {
            from = detectCityFromIP();
            if (from == null) {
                return "Please tell me which city you're departing from.";
            }
        }

        try {
            // Resolve station names
            String fromStation = resolveStation(from);
            if (fromStation == null) {
                return "Could not find a train station in: " + from + ". Try a specific station name (e.g. \"杭州东\").";
            }
            String toStation = resolveStation(to);
            if (toStation == null) {
                return "Could not find station: " + to + ". Try the full station name.";
            }

            // Step 2: Query tickets
            JsonNode result = callMcp("query-tickets", Map.of(
                "from_station", fromStation,
                "to_station", toStation,
                "train_date", date
            ));

            if (result == null) {
                return "Ticket query service is not available. Please try again later.";
            }

            boolean success = result.path("success").asBoolean(false);
            if (!success) {
                String error = result.path("error").asText("unknown error");
                return "Ticket query failed: " + error;
            }

            // Result IS the data — no nested "data" field
            return formatResult(result, fromStation, toStation, date);

        } catch (Exception e) {
            return "Ticket query failed: " + e.getMessage();
        }
    }

    /**
     * Resolve a station name via mcp-server-12306's search-stations.
     * Returns the best-match station name (Chinese), or null.
     */
    private String resolveStation(String keyword) throws Exception {
        JsonNode result = callMcp("search-stations", Map.of("keywords", keyword));
        if (result == null || !result.path("success").asBoolean(false)) {
            return keyword;
        }

        JsonNode data = result.path("data");
        if (data.isArray() && !data.isEmpty()) {
            // Return the first match's name
            String name = data.get(0).path("name").asText(null);
            if (name != null && !name.isEmpty()) return name;
        }

        return keyword; // Fallback
    }

    /**
     * Auto-detect the user's city from IP and search for train stations there.
     * Returns a station name (e.g. "杭州东") or null.
     */
    private String detectCityFromIP() {
        JsonNode root = ipGeoTool.getLocationData();
        if (root == null || !"success".equals(root.path("status").asText())) return null;
        String city = root.path("city").asText("");
        if (city.isEmpty()) return null;

        // Search stations in this city via MCP
        try {
            JsonNode result = callMcp("search-stations", Map.of("keywords", city));
            if (result != null && result.path("success").asBoolean(false)) {
                JsonNode data = result.path("data");
                if (data.isArray() && !data.isEmpty()) {
                    return data.get(0).path("name").asText(null);
                }
            }
        } catch (Exception ignored) {}

        return city;
    }

    /**
     * Call an MCP tool via tools/call.
     * Lazily initializes an MCP session on first call.
     */
    private synchronized JsonNode callMcp(String toolName, Map<String, String> arguments) throws Exception {
        if (mcpSessionId == null) {
            initMcpSession();
        }
        return callMcpTool(toolName, arguments);
    }

    /**
     * MCP Streamable HTTP: send initialize request, capture session ID from response header.
     */
    private void initMcpSession() throws Exception {
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", "2025-03-26");
        ObjectNode capabilities = objectMapper.createObjectNode();
        initParams.set("capabilities", capabilities);
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "mindpet-bot");
        clientInfo.put("version", "1.0.0");
        initParams.set("clientInfo", clientInfo);

        // Make a raw HTTP call to get the session header
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "initialize");
        body.put("id", 0);
        body.set("params", initParams);

        String jsonBody = objectMapper.writeValueAsString(body);
        URL url = new URI(MCP_URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            return; // Will retry on next call
        }

        // Extract session ID from response header
        mcpSessionId = conn.getHeaderField("Mcp-Session-Id");
        if (mcpSessionId != null && !mcpSessionId.isEmpty()) {
            // Send initialized notification (required by MCP spec)
            ObjectNode notif = objectMapper.createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", "notifications/initialized");
            HttpURLConnection conn2 = (HttpURLConnection) url.openConnection();
            conn2.setRequestMethod("POST");
            conn2.setRequestProperty("Content-Type", "application/json");
            conn2.setRequestProperty("Mcp-Session-Id", mcpSessionId);
            conn2.setDoOutput(true);
            conn2.setConnectTimeout(5000);
            conn2.setReadTimeout(5000);
            try (OutputStream os2 = conn2.getOutputStream()) {
                os2.write(objectMapper.writeValueAsString(notif).getBytes(StandardCharsets.UTF_8));
            }
            conn2.getResponseCode(); // consume response
        }
    }

    /**
     * Send a tools/call request and extract the inner tool result.
     * MCP wraps results in: {"result":{"content":[{"type":"text","text":"{...}"}]}}
     * This method unwraps and returns the parsed inner JSON.
     */
    private JsonNode callMcpTool(String toolName, Map<String, String> arguments) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "tools/call");
        body.put("id", 1);

        ObjectNode callParams = objectMapper.createObjectNode();
        callParams.put("name", toolName);
        ObjectNode argsNode = objectMapper.createObjectNode();
        for (Map.Entry<String, String> e : arguments.entrySet()) {
            argsNode.put(e.getKey(), e.getValue());
        }
        callParams.set("arguments", argsNode);
        body.set("params", callParams);

        JsonNode rpcResponse = sendHttpRequest(body);
        if (rpcResponse == null) return null;

        // Unwrap: result.content[0].text contains the tool's JSON response
        JsonNode rpcResult = rpcResponse.path("result");
        JsonNode content = rpcResult.path("content");
        if (content.isArray() && !content.isEmpty()) {
            String textJson = content.get(0).path("text").asText("");
            if (!textJson.isEmpty()) {
                return objectMapper.readTree(textJson);
            }
        }
        // Fallback: return the raw result node
        return rpcResult;
    }

    /**
     * Send a raw JSON-RPC body to the MCP server, returning parsed response JSON.
     */
    private JsonNode sendHttpRequest(ObjectNode body) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);

        URL url = new URI(MCP_URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (mcpSessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", mcpSessionId);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            if (status == 400) mcpSessionId = null; // Session expired, re-init next time
            return null;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return objectMapper.readTree(reader);
        }
    }

    /**
     * Format ticket query results for LLM consumption.
     */
    private String formatResult(JsonNode result, String from, String to, String date) {
        StringBuilder sb = new StringBuilder();

        int count = result.path("count").asInt(0);
        sb.append("【").append(from).append(" → ").append(to).append("】").append(date)
            .append("  共 ").append(count).append(" 趟\n");
        sb.append("=".repeat(50)).append("\n");

        JsonNode trains = result.path("trains");
        if (!trains.isArray() || trains.isEmpty()) {
            sb.append("查询成功，但该日期暂无车次。建议换其他日期或车次试试。\n");
            return sb.toString().trim();
        }

        int shown = 0;
        for (JsonNode train : trains) {
            if (shown >= 20) {
                sb.append("... (showing 20 of ").append(count).append(" trains)\n");
                break;
            }

            String trainCode = train.path("train_no").asText("?");
            String fromStation = train.path("from_station").asText("?");
            String toStation = train.path("to_station").asText("?");
            String startTime = train.path("start_time").asText("");
            String arriveTime = train.path("arrive_time").asText("");
            String duration = train.path("duration").asText("?");

            sb.append(trainCode).append("  ").append(fromStation)
                .append(" ").append(startTime)
                .append(" → ").append(toStation)
                .append(" ").append(arriveTime)
                .append("  |  ").append(duration).append("\n");

            // Seat info: values are direct strings like "有", "20", "--"
            JsonNode seats = train.path("seats");
            if (seats.isObject() && !seats.isEmpty()) {
                var seatFields = seats.fields();
                while (seatFields.hasNext()) {
                    var entry = seatFields.next();
                    String seatName = switch (entry.getKey()) {
                        case "business" -> "商务座";
                        case "first_class" -> "一等座";
                        case "second_class" -> "二等座";
                        case "soft_sleeper" -> "软卧";
                        case "hard_sleeper" -> "硬卧";
                        case "hard_seat" -> "硬座";
                        case "no_seat" -> "无座";
                        default -> entry.getKey();
                    };
                    String seatValue = entry.getValue().asText("");
                    if (!seatValue.isEmpty() && !"--".equals(seatValue) && !"无".equals(seatValue)) {
                        // "有" means available, numbers mean remaining count
                        String display = "有".equals(seatValue) ? "有票" : seatValue + "张";
                        sb.append("  ").append(seatName).append(": ").append(display).append("\n");
                    }
                }
            } else {
                // Fallback to old field-based format
                appendSeatField(sb, train, "swz_num", null, "商务座");
                appendSeatField(sb, train, "zy_num", null, "一等座");
                appendSeatField(sb, train, "ze_num", null, "二等座");
                appendSeatField(sb, train, "rw_num", null, "软卧");
                appendSeatField(sb, train, "yw_num", null, "硬卧");
                appendSeatField(sb, train, "yz_num", null, "硬座");
                appendSeatField(sb, train, "wz_num", null, "无座");
            }

            sb.append("\n");
            shown++;
        }

        return sb.toString().trim();
    }

    private void appendSeatField(StringBuilder sb, JsonNode train,
                                  String numField, String priceField, String label) {
        JsonNode numNode = train.path(numField);
        if (numNode.isMissingNode()) return;
        String num = numNode.asText("");
        if (num.isEmpty() || "0".equals(num) || "--".equals(num) || "无".equals(num)) return;

        sb.append("  ").append(label).append(": ").append(num);
        JsonNode priceNode = train.path(priceField);
        if (!priceNode.isMissingNode()) {
            String price = priceNode.asText("");
            if (!price.isEmpty() && !"--".equals(price)) {
                sb.append(" / ¥").append(price);
            }
        }
        sb.append("\n");
    }
}
