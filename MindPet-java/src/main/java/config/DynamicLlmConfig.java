package config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Electron owns credential persistence; Java keeps an explicit runtime snapshot. */
@Component
public class DynamicLlmConfig {
    public record Snapshot(String apiKey, String baseUrl, String model, String systemPrompt) {}
    private volatile Snapshot current;
    private final RuntimeConfigFile file;
    public DynamicLlmConfig() { this(Path.of("llm-dynamic-config.json")); }
    DynamicLlmConfig(Path path) { file = new RuntimeConfigFile(path); }

    @PostConstruct
    public void loadFromFile() {
        try {
            Map<String,String> data = file.read();
            // Legacy credentials remain usable for this process, then leave only metadata on disk.
            if (data.containsKey("apiKey")) {
                current = new Snapshot(value(data.get("apiKey")), value(data.get("baseUrl")),
                    value(data.get("model")), value(data.get("systemPrompt")));
                persistMetadata();
            }
        } catch (IOException e) { throw new IllegalStateException("无法清理旧版 LLM 配置中的明文密钥", e); }
    }

    public boolean hasOverride() { return current != null; }
    public Snapshot snapshot() { return current; }
    public String getApiKey() { Snapshot s = current; return s == null ? "" : s.apiKey(); }
    public String getBaseUrl() { Snapshot s = current; return s == null ? "" : s.baseUrl(); }
    public String getModel() { Snapshot s = current; return s == null ? "" : s.model(); }
    public String getSystemPrompt() { Snapshot s = current; return s == null ? "" : s.systemPrompt(); }
    public boolean hasSystemPrompt() { return !getSystemPrompt().isBlank(); }
    public synchronized void update(String apiKey, String baseUrl, String model, String systemPrompt) {
        current = new Snapshot(value(apiKey), value(baseUrl), value(model), value(systemPrompt));
        persistMetadata();
    }
    private void persistMetadata() {
        Snapshot s = current;
        if (s == null) return;
        try { file.write(Map.of("baseUrl", s.baseUrl(), "model", s.model(), "systemPrompt", s.systemPrompt())); }
        catch (IOException e) { throw new IllegalStateException("无法保存 LLM 配置元数据", e); }
    }
    // An explicit empty key must never revive a static credential at a different endpoint.
    public String effectiveApiKey(String fallback) { Snapshot s = current; return s == null ? fallback : s.apiKey(); }
    public String effectiveBaseUrl(String fallback) { Snapshot s = current; return s == null ? fallback : s.baseUrl(); }
    public String effectiveModel(String fallback) { Snapshot s = current; return s == null ? fallback : s.model(); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
