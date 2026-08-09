package config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "weather")
public class WeatherConfig {
    private Api api = new Api();

    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }

    public static class Api {
        private String key;
        private String baseUrl = "https://wttr.in/";
        private String units;
        private String lang = "zh";

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getUnits() { return units; }
        public void setUnits(String units) { this.units = units; }
        public String getLang() { return lang; }
        public void setLang(String lang) { this.lang = lang; }
    }
}
