package config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmConfig {
    private Api api = new Api();
    private String model;

    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public static class Api {
        private String key;
        private String url;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
