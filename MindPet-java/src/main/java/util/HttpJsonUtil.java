package util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HttpJsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpJsonUtil() {
    }

    public static JsonNode getJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept-Charset", "UTF-8");
        conn.setRequestProperty("User-Agent", "mindpet-weather-bot/1.0");

        int status = conn.getResponseCode();
        if (status >= 200 && status < 300) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return MAPPER.readTree(reader);
            }
        } else {
            // Read error response for debugging
            StringBuilder errorBody = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errorBody.append(line);
                }
            }
            throw new RuntimeException("HTTP " + status + ": " + errorBody);
        }
    }
}
