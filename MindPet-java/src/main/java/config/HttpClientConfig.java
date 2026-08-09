package config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

/**
 * Spring AI 的 RestClient 自动检测 classpath 上的 OkHttp 并使用它，
 * 但 OkHttp 默认 readTimeout 只有 10 秒，LLM 调用经常超时。
 * 这里显式创建 RestClient.Builder，将超时提到 120 秒。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        return RestClient.builder()
            .requestFactory(new OkHttp3ClientHttpRequestFactory(client));
    }
}
