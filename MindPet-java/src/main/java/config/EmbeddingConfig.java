package config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Embedding 的运行时配置（前端设置页写入）。
 *
 * <p>与 {@link LlmConfig} / {@code DynamicLlmConfig} 的关系：
 * <ul>
 *   <li>LLM 对话用 {@code DynamicLlmConfig}（豆包 / DeepSeek 等）</li>
 *   <li>Embedding 可能来自**另一个厂商**（例如 DeepSeek 做对话 + 豆包做向量），
 *       因此需要独立的 Key 与配置，不能复用 {@code DynamicLlmConfig}</li>
 * </ul>
 *
 * <p>持久化为运行目录下的 {@code embedding-config.json}，被 gitignore 排除。
 * Key 在 Electron 侧加密存储（{@code secret://system-embedding-api-key}），
 * 由主进程解密后随配置同步到后端；后端自身不做加密。
 */
@Component
public class EmbeddingConfig {

    private static final String CONFIG_FILE = "embedding-config.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    /** AUTO: 优先 Ollama，不可用则降级豆包；OLLAMA / DOUBAO: 强制指定 */
    public enum Mode { AUTO, OLLAMA, DOUBAO }

    private volatile String mode = Mode.AUTO.name();
    private volatile String doubaoApiKey;
    private volatile String doubaoEndpoint;
    private volatile String doubaoModel;

    @PostConstruct
    public void loadFromFile() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> data = mapper.readValue(file, Map.class);
            String m = data.get("mode");
            if (m != null && !m.isBlank()) this.mode = m.trim().toUpperCase();
            this.doubaoApiKey = data.getOrDefault("doubaoApiKey", "");
            this.doubaoEndpoint = data.getOrDefault("doubaoEndpoint", "");
            this.doubaoModel = data.getOrDefault("doubaoModel", "");
            System.out.println("[EmbeddingConfig] 已恢复配置: mode=" + mode
                + " hasKey=" + hasDoubaoApiKey());
        } catch (IOException e) {
            System.err.println("[EmbeddingConfig] 读取配置失败: " + e.getMessage());
        }
    }

    /** 一次性更新并持久化。空字符串表示"该字段未提供"，不会覆盖已有值。 */
    public synchronized void update(String mode, String doubaoApiKey,
                                    String doubaoEndpoint, String doubaoModel) {
        if (mode != null && !mode.isBlank()) {
            String normalized = mode.trim().toUpperCase();
            if (isValidMode(normalized)) this.mode = normalized;
        }
        if (doubaoApiKey != null && !doubaoApiKey.isBlank()) this.doubaoApiKey = doubaoApiKey.trim();
        if (doubaoEndpoint != null && !doubaoEndpoint.isBlank()) this.doubaoEndpoint = doubaoEndpoint.trim();
        if (doubaoModel != null && !doubaoModel.isBlank()) this.doubaoModel = doubaoModel.trim();
        saveToFile();
    }

    /** 显式清除豆包 Key（前端"清除密钥"操作）。 */
    public synchronized void clearDoubaoApiKey() {
        this.doubaoApiKey = "";
        saveToFile();
    }

    public static boolean isValidMode(String value) {
        if (value == null) return false;
        try {
            Mode.valueOf(value.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String getMode() { return mode; }
    public Mode modeEnum() {
        try {
            return Mode.valueOf(mode);
        } catch (Exception e) {
            return Mode.AUTO;
        }
    }

    public String getDoubaoApiKey() { return doubaoApiKey; }
    public boolean hasDoubaoApiKey() {
        return doubaoApiKey != null && !doubaoApiKey.isBlank();
    }
    public String getDoubaoEndpoint() { return doubaoEndpoint; }
    public String getDoubaoModel() { return doubaoModel; }

    private void saveToFile() {
        try {
            Map<String, String> data = Map.of(
                "mode", mode == null ? Mode.AUTO.name() : mode,
                "doubaoApiKey", doubaoApiKey == null ? "" : doubaoApiKey,
                "doubaoEndpoint", doubaoEndpoint == null ? "" : doubaoEndpoint,
                "doubaoModel", doubaoModel == null ? "" : doubaoModel
            );
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), data);
        } catch (IOException e) {
            System.err.println("[EmbeddingConfig] 保存配置失败: " + e.getMessage());
        }
    }
}
