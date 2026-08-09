package config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * 运行时可动态更新的 LLM 配置，持久化到 JSON 文件。
 * 前端（AgentPet 设置页）修改 API Key/模型后，通过 API 写入此处。
 * 优先级高于 application.yml 中的静态配置，重启后自动恢复。
 */
@Component
public class DynamicLlmConfig {

    private static final String CONFIG_FILE = "llm-dynamic-config.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    private volatile String apiKey;
    private volatile String baseUrl;
    private volatile String model;
    private volatile String systemPrompt;

    /** 启动时从文件恢复上次保存的配置 */
    @PostConstruct
    public void loadFromFile() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> data = mapper.readValue(file, Map.class);
            this.apiKey = data.getOrDefault("apiKey", "");
            this.baseUrl = data.getOrDefault("baseUrl", "");
            this.model = data.getOrDefault("model", "");
            this.systemPrompt = data.getOrDefault("systemPrompt", "");
            if (hasOverride()) {
                System.out.println("[DynamicLlmConfig] 已从文件恢复配置: model=" + model
                    + " baseUrl=" + baseUrl);
            }
        } catch (IOException e) {
            System.err.println("[DynamicLlmConfig] 读取配置文件失败: " + e.getMessage());
        }
    }

    /** 是否有前端配置覆盖 */
    public boolean hasOverride() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }
    public boolean hasSystemPrompt() { return systemPrompt != null && !systemPrompt.isBlank(); }

    /** 一次性更新所有字段，并持久化 */
    public void update(String apiKey, String baseUrl, String model, String systemPrompt) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.systemPrompt = systemPrompt;
        saveToFile();
    }

    private void saveToFile() {
        try {
            Map<String, String> data = Map.of(
                "apiKey", apiKey != null ? apiKey : "",
                "baseUrl", baseUrl != null ? baseUrl : "",
                "model", model != null ? model : "",
                "systemPrompt", systemPrompt != null ? systemPrompt : ""
            );
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), data);
        } catch (IOException e) {
            System.err.println("[DynamicLlmConfig] 保存配置文件失败: " + e.getMessage());
        }
    }

    /** 获取当前生效的 API Key（动态 > 静态） */
    public String effectiveApiKey(String staticKey) {
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : staticKey;
    }

    /** 获取当前生效的 Base URL */
    public String effectiveBaseUrl(String staticUrl) {
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : staticUrl;
    }

    /** 获取当前生效的模型 */
    public String effectiveModel(String staticModel) {
        return (model != null && !model.isBlank()) ? model : staticModel;
    }
}
