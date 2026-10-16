package config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Runtime credentials are supplied by Electron's OS-encrypted vault. */
@Component
public class EmbeddingConfig {
    public enum Mode { AUTO, OLLAMA, DOUBAO }
    private record Snapshot(String mode, String key, String endpoint, String model) {}
    private volatile Snapshot current;
    private final RuntimeConfigFile file;
    public EmbeddingConfig() { this(Path.of("embedding-config.json")); }
    public EmbeddingConfig(Path path) { file = new RuntimeConfigFile(path); }
    @PostConstruct
    public void loadFromFile() {
        try {
            Map<String,String> data = file.read();
            if (data.containsKey("doubaoApiKey")) {
                current = new Snapshot(normalize(data.get("mode")), value(data.get("doubaoApiKey")),
                    value(data.get("doubaoEndpoint")), value(data.get("doubaoModel")));
                persistMetadata();
            }
        } catch (IOException e) { throw new IllegalStateException("无法清理旧版向量配置中的明文密钥", e); }
    }
    /** Full replacement: an empty key clears the runtime credential. */
    public synchronized void update(String mode, String key, String endpoint, String model) {
        current = new Snapshot(normalize(mode), value(key), value(endpoint), value(model));
        persistMetadata();
    }
    public synchronized void clearDoubaoApiKey() {
        update(getMode(), "", getDoubaoEndpoint(), getDoubaoModel());
    }
    public boolean hasOverride() { return current != null; }
    public static boolean isValidMode(String mode) {
        try { Mode.valueOf(value(mode).toUpperCase(Locale.ROOT)); return true; }
        catch (IllegalArgumentException e) { return false; }
    }
    private static String normalize(String mode) {
        return isValidMode(mode) ? mode.trim().toUpperCase(Locale.ROOT) : Mode.AUTO.name();
    }
    public String getMode() { Snapshot s = current; return s == null ? Mode.AUTO.name() : s.mode(); }
    public Mode modeEnum() { return Mode.valueOf(getMode()); }
    public String getDoubaoApiKey() { Snapshot s = current; return s == null ? "" : s.key(); }
    public boolean hasDoubaoApiKey() { return !getDoubaoApiKey().isBlank(); }
    public String getDoubaoEndpoint() { Snapshot s = current; return s == null ? "" : s.endpoint(); }
    public String getDoubaoModel() { Snapshot s = current; return s == null ? "" : s.model(); }
    private void persistMetadata() {
        Snapshot s = current;
        if (s == null) return;
        try { file.write(Map.of("mode", s.mode(), "doubaoEndpoint", s.endpoint(), "doubaoModel", s.model())); }
        catch (IOException e) { throw new IllegalStateException("无法保存向量配置元数据", e); }
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
