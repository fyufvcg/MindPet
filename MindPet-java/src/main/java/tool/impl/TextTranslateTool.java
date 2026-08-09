package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import util.HttpJsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class TextTranslateTool {

    @Tool(description = "Translate text")
    public String translateText(
            @ToolParam(description = "Text to translate") String text,
            @ToolParam(description = "Source language, default auto") String sourceLang,
            @ToolParam(description = "Target language, e.g. zh-CN, en, ja") String targetLang) {

        String target = targetLang != null ? targetLang.toLowerCase() : null;
        String source = sourceLang != null ? sourceLang.toLowerCase() : "auto";

        if (text == null || text.isEmpty() || target == null || target.isEmpty()) {
            return "Error: missing translation parameters";
        }
        try {
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + encode(source)
                + "&tl=" + encode(target) + "&dt=t&q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            JsonNode root = HttpJsonUtil.getJson(url);
            StringBuilder sb = new StringBuilder();
            JsonNode sentences = root.get(0);
            if (sentences != null && sentences.isArray()) {
                for (JsonNode sentence : sentences) {
                    if (sentence.size() > 0) sb.append(sentence.get(0).asText());
                }
            }
            String detected = root.size() > 2 ? root.get(2).asText("") : "";
            return "Translation: " + sb + (detected.isEmpty() ? "" : "\nDetected: " + detected);
        } catch (Exception e) {
            return "Translation failed: " + e.getMessage();
        }
    }

    private String encode(String value) {
        return value == null ? "auto" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
