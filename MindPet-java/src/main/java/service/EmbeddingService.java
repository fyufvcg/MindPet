package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
public class EmbeddingService {

    private final boolean useOllama;
    private final String ollamaEndpoint;
    private final String ollamaModel;
    private final String ollamaKeepAlive;
    private final String doubaoEndpoint;
    private final String doubaoModel;
    private final String doubaoApiKey;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest;

    public EmbeddingService(
        @Value("${app.embedding.use-ollama:true}") boolean useOllama,
        @Value("${app.embedding.ollama.endpoint:http://127.0.0.1:11434/api/embed}") String ollamaEndpoint,
        @Value("${app.embedding.ollama.model:bge-m3}") String ollamaModel,
        @Value("${app.embedding.ollama.keep-alive:30m}") String ollamaKeepAlive,
        @Value("${app.embedding.doubao.endpoint}") String doubaoEndpoint,
        @Value("${app.embedding.doubao.model}") String doubaoModel,
        @Value("${app.embedding.doubao.api-key}") String doubaoApiKey,
        Logger logger) {
        this.useOllama = useOllama;
        this.ollamaEndpoint = ollamaEndpoint;
        this.ollamaModel = ollamaModel;
        this.ollamaKeepAlive = ollamaKeepAlive;
        this.doubaoEndpoint = doubaoEndpoint;
        this.doubaoModel = doubaoModel;
        this.doubaoApiKey = doubaoApiKey;
        this.logger = logger;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.rest = new RestTemplate(factory);

        logger.log("INFO", "Embedding service initialized -> "
            + (useOllama ? "Ollama/" + ollamaModel : "Doubao/" + doubaoModel));
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return useOllama ? embedWithOllama(text) : embedWithDoubao(text);
        } catch (Exception e) {
            logger.log("ERROR", "Embedding failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    private float[] embedWithOllama(String text) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", ollamaModel,
            "input", text,
            "truncate", true,
            "keep_alive", ollamaKeepAlive
        ));
        JsonNode root = post(ollamaEndpoint, body, null);
        JsonNode embeddings = root.path("embeddings");
        JsonNode embedding = embeddings.isArray() && !embeddings.isEmpty()
            ? embeddings.get(0)
            : null;
        return toVector(embedding, "Ollama");
    }

    private float[] embedWithDoubao(String text) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", doubaoModel,
            "input", List.of(Map.of("type", "text", "text", text)),
            "dimensions", 1024
        ));
        JsonNode root = post(doubaoEndpoint, body, doubaoApiKey);
        return toVector(root.path("data").path("embedding"), "Doubao");
    }

    private JsonNode post(String endpoint, String body, String apiKey) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);

        ResponseEntity<String> resp = rest.exchange(
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
