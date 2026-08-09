package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.DiDiMcpClient;
import service.TencentMapService;
import util.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 滴滴打车工具 — 通过 DiDiMcpClient 调用滴滴官方云端 MCP 服务。
 * 放到"出行"工具组，与车票/路线规划并列。
 */
@Component
public class DiDiRideTool {

    private final DiDiMcpClient mcp;
    private final TencentMapService tencentMap;
    private final Logger logger;

    @Autowired
    public DiDiRideTool(DiDiMcpClient mcp, TencentMapService tencentMap, Logger logger) {
        this.mcp = mcp;
        this.tencentMap = tencentMap;
        this.logger = logger;
    }

    @Tool(description = """
        搜索地点，获取经纬度（使用腾讯地图，比滴滴搜索更准）。
        keyword 越简洁越好（如 高桥云港、湖滨银泰）。
        返回地点名称、地址、城市、经纬度（lng/lat）。
        """)
    public String didi_searchPlace(
            @ToolParam(description = "地点关键词，如 高桥云港、湖滨银泰") String keyword) {
        return tencentMap.search(keyword);
    }

    @Tool(description = """
        预估打车费用。传入起点和终点的经纬度与名称，获取可用车型及预估价格。
        返回各车型名称、预估金额、traceId（用于后续下单）。
        ⚠️ 此接口只预估不叫车，必须等用户确认后才能下单！
        """)
    public String didi_estimateRide(
            @ToolParam(description = "起点经度") String fromLng,
            @ToolParam(description = "起点纬度") String fromLat,
            @ToolParam(description = "起点名称") String fromName,
            @ToolParam(description = "终点经度") String toLng,
            @ToolParam(description = "终点纬度") String toLat,
            @ToolParam(description = "终点名称") String toName) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from_lng", fromLng);
        args.put("from_lat", fromLat);
        args.put("from_name", fromName);
        args.put("to_lng", toLng);
        args.put("to_lat", toLat);
        args.put("to_name", toName);
        JsonNode result = mcp.callTool("taxi_estimate", args);
        return format(result);
    }

    @Tool(description = """
        创建打车订单（真正叫车）。
        ⚠️ 必须先调用 didi_estimateRide 获取 traceId，并且必须等用户明确确认后才能调用此工具！
        productCategory 从预估结果中选取（如 express、comfort、premier 等）。
        """)
    public String didi_createOrder(
            @ToolParam(description = "预估返回的 traceId") String traceId,
            @ToolParam(description = "车型标识（如 express、comfort、premier），从预估结果中选") String productCategory) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("estimate_trace_id", traceId);
        args.put("product_category", productCategory);
        JsonNode result = mcp.callTool("taxi_create_order", args);
        return format(result);
    }

    @Tool(description = "查询打车订单状态（司机位置、预计到达时间等）。")
    public String didi_queryOrder(
            @ToolParam(description = "订单ID") String orderId) {
        JsonNode result = mcp.callTool("taxi_query_order", Map.of("order_id", orderId));
        return format(result);
    }

    @Tool(description = "取消打车订单。")
    public String didi_cancelOrder(
            @ToolParam(description = "订单ID") String orderId) {
        JsonNode result = mcp.callTool("taxi_cancel_order", Map.of("order_id", orderId));
        return format(result);
    }

    private String format(JsonNode result) {
        if (result == null) return "滴滴服务暂不可用，请稍后重试。";
        if (result.has("error")) {
            return "滴滴返回错误：" + result.path("error").path("message").asText("未知错误");
        }
        // unwrap MCP result: {result:{content:[{type:"text", text:"..."}]}}
        JsonNode rpcResult = result.path("result");
        JsonNode content = rpcResult.path("content");
        if (content.isArray() && !content.isEmpty()) {
            String text = content.get(0).path("text").asText("");
            if (!text.isBlank()) {
                try {
                    // text 是 JSON 数组字符串，解析后格式化
                    JsonNode items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
                    if (items.isArray() && !items.isEmpty()) {
                        StringBuilder sb = new StringBuilder("找到 " + items.size() + " 个地点:\n");
                        int count = 0;
                        String firstCity = items.get(0).path("city").asText("");
                        boolean allSameCity = true;
                        for (JsonNode item : items) {
                            if (count++ >= 5) { sb.append("... (共 " + items.size() + " 个)\n"); break; }
                            String name = item.path("display_name").asText("?");
                            String city = item.path("city").asText("");
                            if (!city.equals(firstCity)) allSameCity = false;
                            JsonNode loc = item.path("location");
                            String lng = loc.path("lng").asText("");
                            String lat = loc.path("lat").asText("");
                            sb.append(count).append(". ").append(name);
                            if (!city.isBlank()) sb.append(" | ").append(city);
                            if (!lng.isBlank()) sb.append(" | (").append(lng).append(",").append(lat).append(")");
                            sb.append("\n");
                        }
                        if (allSameCity) {
                            sb.append("⚠ 所有结果都在 ").append(firstCity).append("。如果期望的城市不对，请用更简短的关键词重试（如只用路名或地标名）。\n");
                        }
                        return sb.toString().trim();
                    }
                } catch (Exception e) {
                    // 不是 JSON 数组，直接返回文本
                }
                return text;
            }
        }
        if (rpcResult.has("text")) return rpcResult.path("text").asText();
        return result.toString();
    }
}
