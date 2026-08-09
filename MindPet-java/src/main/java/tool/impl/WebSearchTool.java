package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool {

    @Tool(description = "Search the web for real-time information, news, facts, or anything beyond your knowledge cutoff.")
    public String webSearch(
            @ToolParam(description = "The search query string") String query) {
        // Handled by 豆包 LLM provider internally — Java side is a no-op shell
        return "Search completed for: " + (query != null ? query : "unknown");
    }
}
