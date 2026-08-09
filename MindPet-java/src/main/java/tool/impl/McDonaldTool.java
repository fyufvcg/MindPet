package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 麦当劳点餐工具 — 封装多步 MCP 调用，让 LLM 不需要理解内部流程。
 * 后端负责：查门店 → 查菜单 → 下单，LLM 只需给地址。
 */
@Component
public class McDonaldTool {

    private final service.McpManager mcpManager;

    @Autowired
    public McDonaldTool(service.McpManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    @Tool(description = "在麦当劳下单。告诉我要什么、送到哪，剩下的我来处理。")
    public String mcdonaldOrder(
            @ToolParam(description = "送餐地址（如'杭州市余杭区阿里巴巴园区'）") String address,
            @ToolParam(description = "想要的食物描述（如'巨无霸套餐'、'美芝芝拉丝鸡肉堡'），可选") String foodDesc,
            @ToolParam(description = "备注，如'不要辣'、'多加冰'，可选") String remark) {

        StringBuilder report = new StringBuilder();
        report.append("🍔 麦当劳点餐流程\n\n");

        // Step 1: 查附近门店
        report.append("📍 正在查找附近门店...\n");
        String stores = callMcp("delivery_query_stores", java.util.Map.of("address", address != null ? address : ""));
        report.append(stores).append("\n\n");

        // Step 2: 选最近的门店，查菜单
        report.append("📋 正在查询菜单...\n");
        String meals = callMcp("query_meals", java.util.Map.of());
        report.append(meals).append("\n\n");

        // Step 3: 如果有食物描述，尝试创建订单
        if (foodDesc != null && !foodDesc.isBlank()) {
            report.append("🛒 正在下单: ").append(foodDesc).append("\n");
            java.util.Map<String, Object> orderArgs = new java.util.LinkedHashMap<>();
            orderArgs.put("foodName", foodDesc);
            orderArgs.put("remark", remark != null ? remark : "");
            String order = callMcp("create_order", orderArgs);
            report.append(order);
        } else {
            report.append("💡 请告诉我想要什么食物，我帮你下单。");
        }

        return report.toString();
    }

    @Tool(description = "查询麦当劳当前活动日历")
    public String mcdonaldCampaigns() {
        return callMcp("campaign_calendar", java.util.Map.of());
    }

    @Tool(description = "查询麦当劳可用优惠券")
    public String mcdonaldCoupons() {
        return callMcp("available_coupons", java.util.Map.of());
    }

    @Tool(description = "查询麦当劳订单状态")
    public String mcdonaldOrderStatus(
            @ToolParam(description = "订单号，可选，不传则查最近订单") String orderId) {
        if (orderId != null && !orderId.isBlank()) {
            return callMcp("query_order", java.util.Map.of("orderId", orderId));
        }
        return callMcp("order_list", java.util.Map.of());
    }

    private String callMcp(String toolName, java.util.Map<String, Object> args) {
        try {
            // 遍历所有 MCP 连接，找到包含此工具的服务器
            for (var cb : mcpManager.getToolCallbacks()) {
                String name = cb.getToolDefinition().name();
                if (name.endsWith("__" + toolName)) {
                    return cb.call(args.isEmpty() ? "{}" :
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args));
                }
            }
            return "❌ 麦当劳 MCP 服务未连接，请在 Settings → MCP 中添加麦当劳服务。";
        } catch (Exception e) {
            return "❌ 调用失败: " + e.getMessage();
        }
    }
}
