package service;

import config.DynamicLlmConfig;
import config.LlmConfig;
import config.ToolCallLimitAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import util.Logger;

/** Builds chat clients from the same static/dynamic configuration for every LLM service. */
@Service
public class DynamicChatClientFactory {

    private final LlmConfig config;
    private final DynamicLlmConfig dynamicConfig;
    private final ChatClient.Builder staticBuilder;
    private final Logger logger;

    @Value("${spring.ai.openai.chat.completions-path:/chat/completions}")
    private String completionsPath;

    private volatile String cachedFingerprint;
    private volatile ChatClient cachedDynamicClient;

    public DynamicChatClientFactory(LlmConfig config, DynamicLlmConfig dynamicConfig,
                                    ChatClient.Builder staticBuilder, Logger logger) {
        this.config = config;
        this.dynamicConfig = dynamicConfig;
        this.staticBuilder = staticBuilder;
        this.logger = logger;
    }

    public boolean isConfigured() {
        return !isBlank(effectiveApiKey()) && !isBlank(effectiveBaseUrl()) && !isBlank(effectiveModel());
    }

    public ChatClient build() {
        if (!dynamicConfig.hasOverride()) return staticBuilder.build();

        String key = dynamicConfig.getApiKey();
        String url = effectiveBaseUrl();
        String fingerprint = url + "|" + key;
        ChatClient current = cachedDynamicClient;
        if (current != null && fingerprint.equals(cachedFingerprint)) return current;

        synchronized (this) {
            if (cachedDynamicClient == null || !fingerprint.equals(cachedFingerprint)) {
                OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(url)
                    .apiKey(key)
                    .completionsPath(completionsPath)
                    .build();
                OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api).build();
                cachedDynamicClient = ChatClient.builder(model)
                    .defaultAdvisors(new org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor(),
                        new ToolCallLimitAdvisor(logger))
                    .build();
                cachedFingerprint = fingerprint;
            }
            return cachedDynamicClient;
        }
    }

    public ChatClient.ChatClientRequestSpec applyCurrentModel(ChatClient.ChatClientRequestSpec spec) {
        if (dynamicConfig.hasOverride() && !isBlank(dynamicConfig.getModel())) {
            return spec.options(OpenAiChatOptions.builder().model(dynamicConfig.getModel()).build());
        }
        return spec;
    }

    public String effectiveModel() {
        return dynamicConfig.effectiveModel(config.getModel());
    }

    private String effectiveApiKey() {
        return dynamicConfig.effectiveApiKey(config.getApi().getKey());
    }

    private String effectiveBaseUrl() {
        String url = dynamicConfig.effectiveBaseUrl(config.getApi().getUrl());
        if (url != null && url.endsWith("/chat/completions")) {
            return url.substring(0, url.length() - "/chat/completions".length());
        }
        return url;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
