package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.HowToCookMcpClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RecipeTools {

    private final HowToCookMcpClient client;

    @Autowired
    public RecipeTools(HowToCookMcpClient client) {
        this.client = client;
    }

    @Tool(description = "获取所有菜谱。")
    public String getAllRecipes() {
        return client.callTool("mcp_howtocook_getAllRecipes", Map.of());
    }

    @Tool(description = "按菜谱分类查询。")
    public String getRecipesByCategory(
        @ToolParam(description = "菜谱分类名称") String category
    ) {
        return client.callTool("mcp_howtocook_getRecipesByCategory", Map.of("category", category));
    }

    @Tool(description = "根据人数、忌口和过敏原推荐一周菜谱。")
    public String recommendMeals(
        @ToolParam(description = "用餐人数") int peopleCount,
        @ToolParam(description = "过敏原，逗号分隔") String allergies,
        @ToolParam(description = "忌口食材，逗号分隔") String avoidItems
    ) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("peopleCount", peopleCount);
        args.put("allergies", splitCsv(allergies));
        args.put("avoidItems", splitCsv(avoidItems));
        return client.callTool("mcp_howtocook_recommendMeals", args);
    }

    @Tool(description = "根据人数推荐今天吃什么。")
    public String whatToEat(
        @ToolParam(description = "用餐人数") int peopleCount
    ) {
        return client.callTool("mcp_howtocook_whatToEat", Map.of("peopleCount", peopleCount));
    }

    @Tool(description = "根据菜谱名称或ID查询详细菜谱。")
    public String getRecipeById(
        @ToolParam(description = "菜谱名称或ID") String query
    ) {
        return client.callTool("mcp_howtocook_getRecipeById", Map.of("query", query));
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("[,，]"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    }
}
