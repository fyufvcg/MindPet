package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.EmbeddingConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import util.Logger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider readiness means a successful, finite 1024-dimensional vector response. */
@Service
public class EmbeddingService {
    public enum Reason {
        OK_OLLAMA, OK_DOUBAO, OLLAMA_UNREACHABLE, OLLAMA_MODEL_MISSING,
        NO_PROVIDER_AVAILABLE, FORCED_OLLAMA_UNAVAILABLE, DOUBAO_KEY_MISSING,
        DOUBAO_REQUEST_FAILED, INVALID_VECTOR, UNKNOWN
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest;
    private final RestTemplate probe;
    private final Logger logger;
    private final EmbeddingConfig config;
    private final String cloudEndpoint, cloudModel, cloudKey;
    private final String ollamaEndpoint, ollamaModel, ollamaKeepAlive;
    private final Object probeLock = new Object();
    private volatile boolean ollamaReachable, ollamaModelPresent, ollamaChecked, doubaoVerified;
    private volatile String ollamaProbeDetail = "", doubaoDetail = "";
    private volatile Reason reason = Reason.UNKNOWN;
    private volatile String activeProvider;
    private volatile long verifiedAt;
    private String configuredFingerprint = "";
    private long nextProbeAt;

    public EmbeddingService(
        @Value("${app.embedding.ollama.endpoint:http://127.0.0.1:11434/api/embed}") String ollamaEndpoint,
        @Value("${app.embedding.ollama.model:bge-m3}") String ollamaModel,
        @Value("${app.embedding.ollama.keep-alive:30m}") String ollamaKeepAlive,
        @Value("${app.embedding.doubao.endpoint:https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal}") String endpoint,
        @Value("${app.embedding.doubao.model:doubao-embedding-vision-251215}") String model,
        @Value("${app.embedding.doubao.api-key:}") String apiKey,
        EmbeddingConfig config, Logger logger) {
        this.ollamaEndpoint = ollamaEndpoint;
        this.ollamaModel = ollamaModel;
        this.ollamaKeepAlive = ollamaKeepAlive;
        this.cloudEndpoint = endpoint;
        this.cloudModel = model;
        // LLM credentials never implicitly cross over to an embedding provider.
        this.cloudKey = apiKey == null ? "" : apiKey;
        this.config = config;
        this.logger = logger;
        this.rest = client(30_000);
        this.probe = client(10_000);
        configure(false);
    }
    private static RestTemplate client(int timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }
    public String activeProviderDescription() {
        String p = activeProvider;
        return p == null ? null : "ollama".equals(p) ? "Ollama/" + ollamaModel : "Doubao/" + doubaoModel();
    }
    public String getActiveProvider() { return activeProvider; }
    public boolean isOllamaReachable() { return ollamaReachable; }
    public boolean isOllamaModelPresent() { return ollamaModelPresent; }
    public String getOllamaModel() { return ollamaModel; }
    public Reason getReason() { return reason; }
    public boolean hasDoubaoApiKey() { return !doubaoApiKey().isBlank(); }
    public String getDoubaoModel() { return doubaoModel(); }

    public Map<String,Object> status() {
        synchronized (probeLock) {
            Map<String,Object> local = new LinkedHashMap<>();
            local.put("endpoint", ollamaEndpoint); local.put("model", ollamaModel);
            local.put("checked", ollamaChecked); local.put("reachable", ollamaReachable);
            local.put("modelPresent", ollamaModelPresent); local.put("detail", ollamaProbeDetail);
            Map<String,Object> cloud = new LinkedHashMap<>();
            cloud.put("endpoint", doubaoEndpoint()); cloud.put("model", doubaoModel());
            cloud.put("configured", hasDoubaoApiKey()); cloud.put("verified", doubaoVerified);
            cloud.put("detail", doubaoDetail);
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("mode", config.getMode()); result.put("activeProvider", activeProvider);
            result.put("activeProviderDescription", activeProviderDescription());
            result.put("available", activeProvider != null); result.put("verifiedAt", verifiedAt);
            result.put("dimension", activeProvider == null ? null : 1024);
            result.put("reason", reason.name()); result.put("hint", hint());
            result.put("ollama", local); result.put("doubao", cloud);
            return result;
        }
    }
    public String hint() {
        return switch (reason) {
            case OK_OLLAMA -> "本地向量检测通过，维度为 1024。";
            case OK_DOUBAO -> "豆包云端向量检测通过，维度为 1024；无需安装 Ollama。";
            case OLLAMA_UNREACHABLE, OLLAMA_MODEL_MISSING -> activeProvider == null
                ? "本地向量模型不可用，请选择云端模式并配置豆包 Key，或检查 Ollama。"
                : "本地向量模型不可用，豆包云端已通过真实向量检测。";
            case FORCED_OLLAMA_UNAVAILABLE -> "本地向量检测未通过：" + ollamaProbeDetail;
            case DOUBAO_KEY_MISSING -> "请填写豆包 Embedding API Key；基础对话可独立使用。";
            case DOUBAO_REQUEST_FAILED -> "云端向量检测未通过：" + doubaoDetail;
            case INVALID_VECTOR -> "向量响应无效，模型必须返回 1024 维有限数值向量。";
            case NO_PROVIDER_AVAILABLE -> "向量服务不可用，请配置云端 Embedding 或检查本地 Ollama；基础对话可独立使用。";
            case UNKNOWN -> "向量服务尚未检测。";
        };
    }
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        synchronized (probeLock) {
            if (activeProvider == null) configure(false);
            String provider = activeProvider;
            if (provider == null) return null;
            try { return requestVector(provider, service.v3.SecretSanitizer.sanitize(text), rest); }
            catch (Exception e) {
                if ("doubao".equals(provider)) { doubaoVerified = false; doubaoDetail = failure(e); }
                else ollamaProbeDetail = failure(e);
                activeProvider = null;
                nextProbeAt = System.currentTimeMillis() + 60_000;
                reason = e instanceof InvalidVectorException ? Reason.INVALID_VECTOR
                    : "doubao".equals(provider) ? Reason.DOUBAO_REQUEST_FAILED : Reason.FORCED_OLLAMA_UNAVAILABLE;
                logger.log("ERROR", "Embedding 请求失败 [" + provider + "]: " + failure(e));
                return null;
            }
        }
    }
    public Map<String,Object> refresh() { return configure(true); }

    public Map<String,Object> applyConfiguration(String mode, String key, String endpoint, String model, boolean verify) {
        synchronized (probeLock) {
            config.update(mode, key, endpoint, model);
            return configure(verify);
        }
    }

    /** Idempotent sync reuses readiness; an explicit check or changed configuration probes once. */
    public Map<String,Object> configure(boolean verify) {
        synchronized (probeLock) {
            String fingerprint = config.getMode() + "|" + doubaoEndpoint() + "|" + doubaoModel() + "|" + doubaoApiKey();
            if (!verify && fingerprint.equals(configuredFingerprint)
                && (activeProvider != null || System.currentTimeMillis() < nextProbeAt)) return status();
            configuredFingerprint = fingerprint;
            nextProbeAt = System.currentTimeMillis() + 60_000;
            activeProvider = null; verifiedAt = 0; doubaoVerified = false; doubaoDetail = "";
            ollamaChecked = false; ollamaReachable = false; ollamaModelPresent = false; ollamaProbeDetail = "";
            EmbeddingConfig.Mode mode = config.modeEnum();
            // Forced cloud must not wait for or require an Ollama installation.
            if (mode != EmbeddingConfig.Mode.DOUBAO) probeOllama();
            if (mode != EmbeddingConfig.Mode.DOUBAO && ollamaModelPresent) {
                activeProvider = "ollama"; reason = Reason.OK_OLLAMA;
            } else if (mode == EmbeddingConfig.Mode.OLLAMA) {
                reason = Reason.FORCED_OLLAMA_UNAVAILABLE;
            } else if (!hasDoubaoApiKey()) {
                reason = mode == EmbeddingConfig.Mode.DOUBAO ? Reason.DOUBAO_KEY_MISSING : Reason.NO_PROVIDER_AVAILABLE;
            } else {
                try {
                    requestVector("doubao", "ping", probe);
                    doubaoVerified = true; activeProvider = "doubao";
                    reason = mode == EmbeddingConfig.Mode.DOUBAO ? Reason.OK_DOUBAO
                        : ollamaReachable ? Reason.OLLAMA_MODEL_MISSING : Reason.OLLAMA_UNREACHABLE;
                } catch (Exception e) {
                    doubaoDetail = failure(e);
                    reason = e instanceof InvalidVectorException ? Reason.INVALID_VECTOR : Reason.DOUBAO_REQUEST_FAILED;
                }
            }
            if (activeProvider != null) verifiedAt = System.currentTimeMillis();
            logger.log("INFO", "Embedding 检测完成 mode=" + mode + " reason=" + reason);
            return status();
        }
    }
    private void probeOllama() {
        ollamaChecked = true;
        try {
            requestVector("ollama", "ping", probe);
            ollamaReachable = true; ollamaModelPresent = true;
        } catch (HttpStatusCodeException e) {
            ollamaReachable = true;
            ollamaProbeDetail = e.getStatusCode().value() == 404 ? "模型未下载或接口地址不正确" : failure(e);
        } catch (Exception e) { ollamaProbeDetail = failure(e); }
    }
    private float[] requestVector(String provider, String text, RestTemplate client) throws Exception {
        boolean local = "ollama".equals(provider);
        Map<String,Object> payload = local
            ? Map.of("model", ollamaModel, "input", text, "truncate", true, "keep_alive", ollamaKeepAlive)
            : Map.of("model", doubaoModel(), "input", List.of(Map.of("type", "text", "text", text)), "dimensions", 1024);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!local) headers.setBearerAuth(doubaoApiKey());
        ResponseEntity<String> response = client.exchange(local ? ollamaEndpoint : doubaoEndpoint(),
            HttpMethod.POST, new HttpEntity<>(mapper.writeValueAsString(payload), headers), String.class);
        JsonNode root = mapper.readTree(response.getBody());
        JsonNode vector;
        if (local) vector = root.path("embeddings").path(0);
        else {
            JsonNode data = root.path("data");
            vector = data.isArray() ? data.path(0).path("embedding") : data.path("embedding");
        }
        return toVector(vector);
    }
    private float[] toVector(JsonNode values) {
        if (!values.isArray() || values.size() != 1024) throw new InvalidVectorException();
        float[] vector = new float[1024];
        for (int i=0; i<vector.length; i++) {
            if (!values.get(i).isNumber()) throw new InvalidVectorException();
            vector[i] = values.get(i).floatValue();
            if (!Float.isFinite(vector[i])) throw new InvalidVectorException();
        }
        return vector;
    }
    private static class InvalidVectorException extends IllegalStateException {}
    private static String failure(Exception error) {
        if (error instanceof InvalidVectorException) return "返回向量为空、非数值或维度不是 1024";
        if (error instanceof HttpStatusCodeException http) {
            int status = http.getStatusCode().value();
            return switch (status) {
                case 401 -> "HTTP 401，密钥无效或已失效";
                case 403 -> "HTTP 403，请检查模型访问权限";
                case 429 -> "HTTP 429，请检查额度或调用频率";
                default -> "服务返回 HTTP " + status + "，请检查服务地址和模型";
            };
        }
        return "连接失败、超时或响应格式错误，请检查网络与服务地址";
    }
    private String doubaoApiKey() { return config.hasOverride() ? config.getDoubaoApiKey() : cloudKey; }
    private String doubaoEndpoint() {
        return config.getDoubaoEndpoint().isBlank() ? cloudEndpoint : config.getDoubaoEndpoint();
    }
    private String doubaoModel() { return config.getDoubaoModel().isBlank() ? cloudModel : config.getDoubaoModel(); }
    public static String toPgVectorString(float[] vector) {
        if (vector == null) return null;
        StringBuilder text = new StringBuilder("[");
        for (int i=0; i<vector.length; i++) { if (i>0) text.append(','); text.append(vector[i]); }
        return text.append(']').toString();
    }
}
