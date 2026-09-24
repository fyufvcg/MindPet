package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.EmbeddingConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import util.Logger;

import java.util.List;
import java.util.Map;

/**
 * Embedding 服务 — 支持 Ollama（本地）与豆包（云端）双通路，并在运行时自动降级。
 *
 * <h3>为什么需要自动降级</h3>
 * 本服务历史上是启动时决定的布尔开关（{@code use-ollama}），一旦 Ollama 不可用，
 * {@link #embed(String)} 会吞掉异常返回 null —— 后端 {@code /health} 与聊天全正常，
 * 但长期记忆**静默失效**。现在改为：
 * <ol>
 *   <li>启动时探测 Ollama（服务可达 + 模型已拉取）</li>
 *   <li>按 {@link EmbeddingConfig.Mode} 三态决定 provider：AUTO 优先 Ollama 并自动降级，
 *       OLLAMA / DOUBAO 为强制指定</li>
 *   <li>决策结果与原因通过 {@link #status()} 暴露给前端展示</li>
 * </ol>
 *
 * <h3>探测为何直接打 /api/embed 而不是 /api/tags</h3>
 * "服务在但模型没拉取"（未执行 {@code ollama pull bge-m3}）是最常见的失败场景，
 * 查模型列表无法区分它与"完全不可用"，因此直接发起一次极小的 embed 请求。
 */
@Service
public class EmbeddingService {

    /** 探测/降级的原因码，供前端展示可读提示 */
    public enum Reason {
        OK_OLLAMA,
        OK_DOUBAO,
        OLLAMA_UNREACHABLE,
        OLLAMA_MODEL_MISSING,
        NO_PROVIDER_AVAILABLE,
        FORCED_OLLAMA_UNAVAILABLE,
        DOUBAO_KEY_MISSING,
        UNKNOWN
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest;
    private final Logger logger;
    private final EmbeddingConfig config;
    private final String doubaoEndpointFromYml;
    private final String doubaoModelFromYml;
    private final String doubaoApiKeyFromYml;
    private final String ollamaEndpoint;
    private final String ollamaModel;
    private final String ollamaKeepAlive;

    /** 探测结果（启动时与手动刷新时更新，加锁读写） */
    private final Object probeLock = new Object();
    private volatile boolean ollamaReachable = false;
    private volatile boolean ollamaModelPresent = false;
    private volatile String ollamaProbeDetail = "";
    private volatile Reason reason = Reason.UNKNOWN;
    /** 当前实际生效的 provider，null 表示两者都不可用 */
    private volatile String activeProvider = null;

    public EmbeddingService(
        @Value("${app.embedding.ollama.endpoint:http://127.0.0.1:11434/api/embed}") String ollamaEndpoint,
        @Value("${app.embedding.ollama.model:bge-m3}") String ollamaModel,
        @Value("${app.embedding.ollama.keep-alive:30m}") String ollamaKeepAlive,
        @Value("${app.embedding.doubao.endpoint}") String doubaoEndpoint,
        @Value("${app.embedding.doubao.model}") String doubaoModel,
        @Value("${app.embedding.doubao.api-key:}") String doubaoApiKey,
        @Value("${llm.api.key:}") String llmApiKey,
        EmbeddingConfig config,
        Logger logger) {
        this.ollamaEndpoint = ollamaEndpoint;
        this.ollamaModel = ollamaModel;
        this.ollamaKeepAlive = ollamaKeepAlive;
        this.doubaoEndpointFromYml = doubaoEndpoint;
        this.doubaoModelFromYml = doubaoModel;
        // 兜底：豆包 Embedding Key 为空时复用 LLM Key（二者同属方舟平台，通常同一个 Key）
        this.doubaoApiKeyFromYml =
            (doubaoApiKey == null || doubaoApiKey.isBlank()) ? llmApiKey : doubaoApiKey;
        this.config = config;
        this.logger = logger;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);   // 探测要快，本机服务不该等 10 秒
        factory.setReadTimeout(30_000);
        this.rest = new RestTemplate(factory);

        resolveProvider(true);
    }

    // ==================== 对外：状态 ====================

    /** 当前生效 provider 描述，例如 "Ollama/bge-m3"、"Doubao/..."、null 表示不可用。 */
    public String activeProviderDescription() {
        String p = activeProvider;
        if (p == null) return null;
        return "ollama".equals(p) ? "Ollama/" + ollamaModel : "Doubao/" + doubaoModel();
    }

    public String getActiveProvider() { return activeProvider; }
    public boolean isOllamaReachable() { return ollamaReachable; }
    public boolean isOllamaModelPresent() { return ollamaModelPresent; }
    public String getOllamaModel() { return ollamaModel; }
    public Reason getReason() { return reason; }
    public boolean hasDoubaoApiKey() { return !doubaoApiKey().isBlank(); }
    public String getDoubaoModel() { return doubaoModel(); }

    /** 供 /embedding-status 结构化输出 */
    public Map<String, Object> status() {
        Map<String, Object> ollama = new java.util.LinkedHashMap<>();
        ollama.put("endpoint", ollamaEndpoint);
        ollama.put("model", ollamaModel);
        ollama.put("reachable", ollamaReachable);
        ollama.put("modelPresent", ollamaModelPresent);
        if (!ollamaProbeDetail.isBlank()) ollama.put("detail", ollamaProbeDetail);

        Map<String, Object> doubao = new java.util.LinkedHashMap<>();
        doubao.put("endpoint", doubaoEndpoint());
        doubao.put("model", doubaoModel());
        doubao.put("configured", hasDoubaoApiKey());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("mode", config.getMode());
        result.put("activeProvider", activeProvider);
        result.put("activeProviderDescription", activeProviderDescription());
        result.put("reason", reason.name());
        result.put("hint", hint());
        result.put("ollama", ollama);
        result.put("doubao", doubao);
        return result;
    }

    /** 人类可读的处置建议，前端直接显示，避免用户看到裸 reason 码 */
    public String hint() {
        return switch (reason) {
            case OK_OLLAMA -> "使用本地 Ollama（" + ollamaModel + "），数据不出本机。";
            case OK_DOUBAO -> "使用豆包云端 Embedding。";
            case OLLAMA_UNREACHABLE ->
                "未检测到本地 Ollama 服务，已自动改用豆包 Embedding。"
                + "若希望使用本地模型：启动 Ollama 后点击「重新检测」。";
            case OLLAMA_MODEL_MISSING ->
                "检测到 Ollama 服务，但未下载模型 " + ollamaModel + "，已自动改用豆包 Embedding。"
                + "下载命令：ollama pull " + ollamaModel;
            case FORCED_OLLAMA_UNAVAILABLE ->
                "当前为「强制本地」模式，但 Ollama 不可用"
                + (ollamaReachable ? "（服务在，模型 " + ollamaModel + " 缺失）" : "（服务未启动）")
                + "。长期记忆将无法工作。";
            case DOUBAO_KEY_MISSING ->
                "当前为「强制云端」模式，但未配置豆包 Embedding API Key。"
                + "请在设置页填写，否则长期记忆无法工作。";
            case NO_PROVIDER_AVAILABLE ->
                "Ollama 与豆包 Embedding 均不可用，长期记忆功能已失效。"
                + "请启动 Ollama 或填写豆包 Embedding API Key。";
            case UNKNOWN -> "";
        };
    }

    // ==================== 对外：嵌入 ====================

    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        String provider = activeProvider;
        if (provider == null) {
            logger.log("ERROR", "Embedding 不可用（无 provider）: " + hint());
            return null;
        }
        try {
            return "ollama".equals(provider) ? embedWithOllama(text) : embedWithDoubao(text);
        } catch (Exception e) {
            logger.log("ERROR", "Embedding failed [" + provider + "]: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    /** 手动重新探测并决策（前端「重新检测」按钮 / 修改配置后调用） */
    public Map<String, Object> refresh() {
        resolveProvider(true);
        return status();
    }

    // ==================== provider 决策 ====================

    private void resolveProvider(boolean doProbe) {
        synchronized (probeLock) {
            if (doProbe) probeOllama();

            EmbeddingConfig.Mode mode = config.modeEnum();
            boolean doubaoUsable = hasDoubaoApiKey();

            switch (mode) {
                case OLLAMA -> {
                    if (ollamaReachable && ollamaModelPresent) {
                        setActive("ollama", Reason.OK_OLLAMA);
                    } else {
                        setActive(null, Reason.FORCED_OLLAMA_UNAVAILABLE);
                    }
                }
                case DOUBAO -> {
                    if (doubaoUsable) {
                        setActive("doubao", Reason.OK_DOUBAO);
                    } else {
                        setActive(null, Reason.DOUBAO_KEY_MISSING);
                    }
                }
                case AUTO -> {
                    if (ollamaReachable && ollamaModelPresent) {
                        setActive("ollama", Reason.OK_OLLAMA);
                    } else if (doubaoUsable) {
                        setActive("doubao", ollamaReachable
                            ? Reason.OLLAMA_MODEL_MISSING : Reason.OLLAMA_UNREACHABLE);
                    } else {
                        setActive(null, ollamaReachable
                            ? Reason.OLLAMA_MODEL_MISSING : Reason.NO_PROVIDER_AVAILABLE);
                    }
                }
            }
            logger.log("INFO", "Embedding provider → " + String.valueOf(activeProviderDescription())
                + " (mode=" + mode + ", reason=" + reason + ")");
        }
    }

    private void setActive(String provider, Reason r) {
        this.activeProvider = provider;
        this.reason = r;
    }

    /**
     * 探测 Ollama：直接发一次极小 embed 请求，能同时判断
     * "服务是否可达" 与 "模型是否已拉取"。
     */
    private void probeOllama() {
        ollamaReachable = false;
        ollamaModelPresent = false;
        ollamaProbeDetail = "";
        try {
            String body = mapper.writeValueAsString(Map.of(
                "model", ollamaModel,
                "input", "ping",
                "truncate", true,
                "keep_alive", ollamaKeepAlive
            ));
            JsonNode root = post(ollamaEndpoint, body, null, 3_000);
            JsonNode embeddings = root.path("embeddings");
            if (embeddings.isArray() && !embeddings.isEmpty()) {
                ollamaReachable = true;
                ollamaModelPresent = true;
            } else {
                ollamaReachable = true;
                ollamaProbeDetail = "响应中没有 embeddings 字段";
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (isModelMissing(msg)) {
                // 服务可达，但模型未拉取
                ollamaReachable = true;
                ollamaModelPresent = false;
                ollamaProbeDetail = "模型未下载";
            } else {
                ollamaReachable = false;
                ollamaProbeDetail = e.getClass().getSimpleName()
                    + (msg.isBlank() ? "" : ": " + truncate(msg, 160));
            }
        }
    }

    /** Ollama 未拉取模型时返回 404 + "model ... not found" */
    private boolean isModelMissing(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("not found") || m.contains("no such model")
            || m.contains("model") && m.contains("404");
    }

    // ==================== 两个实现 ====================

    private float[] embedWithOllama(String text) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", ollamaModel,
            "input", text,
            "truncate", true,
            "keep_alive", ollamaKeepAlive
        ));
        JsonNode root = post(ollamaEndpoint, body, null, 30_000);
        JsonNode embeddings = root.path("embeddings");
        JsonNode embedding = embeddings.isArray() && !embeddings.isEmpty()
            ? embeddings.get(0)
            : null;
        return toVector(embedding, "Ollama");
    }

    private float[] embedWithDoubao(String text) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", doubaoModel(),
            "input", List.of(Map.of("type", "text", "text", text)),
            "dimensions", 1024
        ));
        JsonNode root = post(doubaoEndpoint(), body, doubaoApiKey(), 30_000);
        return toVector(root.path("data").path("embedding"), "Doubao");
    }

    /** 运行时配置优先于 yml（前端设置页可覆盖） */
    private String doubaoApiKey() {
        String fromConfig = config.getDoubaoApiKey();
        return (fromConfig != null && !fromConfig.isBlank()) ? fromConfig : doubaoApiKeyFromYml;
    }

    private String doubaoEndpoint() {
        String fromConfig = config.getDoubaoEndpoint();
        return (fromConfig != null && !fromConfig.isBlank()) ? fromConfig : doubaoEndpointFromYml;
    }

    private String doubaoModel() {
        String fromConfig = config.getDoubaoModel();
        return (fromConfig != null && !fromConfig.isBlank()) ? fromConfig : doubaoModelFromYml;
    }

    private JsonNode post(String endpoint, String body, String apiKey, int readTimeoutMs) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);

        // 探测需要更短的读超时，这里临时构造一个请求工厂
        RestTemplate client = rest;
        if (readTimeoutMs != 30_000) {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(2_000);
            f.setReadTimeout(readTimeoutMs);
            client = new RestTemplate(f);
        }

        ResponseEntity<String> resp = client.exchange(
            endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), String.class
        );

        JsonNode root = mapper.readTree(resp.getBody());
        if (root.has("error")) {
            JsonNode error = root.path("error");
            String message = error.isTextual() ? error.asText() : error.path("message").asText("?");
            throw new IllegalStateException("Embedding API error: " + message);
        }
        return root;
    }

    private float[] toVector(JsonNode embedding, String provider) {
        if (embedding == null || !embedding.isArray()) {
            throw new IllegalStateException(provider + " embedding response format error");
        }
        float[] vec = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) vec[i] = embedding.get(i).floatValue();
        return vec;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static String toPgVectorString(float[] vec) {
        if (vec == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
