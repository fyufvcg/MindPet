package service;

import config.BaiduConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import util.JsonValueUtil;
import util.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class BaiduASRService {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String ASR_URL = "https://vop.baidu.com/server_api";
    private static final long TOKEN_EXPIRE_BUFFER = 5 * 60 * 1000;

    private final Logger logger;
    private final BaiduConfig config;
    private String accessToken;
    private long tokenExpireTime = 0;

    @Autowired
    public BaiduASRService(BaiduConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        logger.log("INFO", "Baidu ASR service initialized");
    }

    public boolean isConfigured() {
        return config.getSpeech().getApi().getKey() != null && !config.getSpeech().getApi().getKey().isEmpty()
            && config.getSpeech().getApi().getSecret() != null && !config.getSpeech().getApi().getSecret().isEmpty();
    }

    public String recognize(byte[] audioData) {
        if (!isConfigured()) {
            logger.log("WARN", "Baidu ASR is not configured");
            return null;
        }

        try {
            if (accessToken == null || System.currentTimeMillis() > tokenExpireTime) {
                refreshToken();
            }
            if (accessToken == null) {
                logger.log("ERROR", "Failed to obtain Baidu ASR access token");
                return null;
            }

            String base64Audio = Base64.getEncoder().encodeToString(audioData);
            String json = String.format(
                "{\"format\":\"amr\",\"rate\":16000,\"channel\":1,\"cuid\":\"weather-bot\",\"token\":\"%s\",\"speech\":\"%s\",\"len\":%d}",
                accessToken, base64Audio, audioData.length
            );

            String response = sendPostRequest(ASR_URL, json);
            return parseResponse(response);
        } catch (Exception e) {
            logger.log("ERROR", "Baidu ASR recognition failed: " + e.getMessage());
            return null;
        }
    }

    private void refreshToken() throws IOException {
        String url = TOKEN_URL
            + "?grant_type=client_credentials&client_id=" + config.getSpeech().getApi().getKey()
            + "&client_secret=" + config.getSpeech().getApi().getSecret();

        String response = sendGetRequest(url);
        String token = JsonValueUtil.extractJsonValue(response, "access_token");
        String expiresIn = JsonValueUtil.extractJsonValue(response, "expires_in");
        if (token != null) {
            accessToken = token;
            if (expiresIn != null) {
                tokenExpireTime = System.currentTimeMillis() + Long.parseLong(expiresIn) * 1000 - TOKEN_EXPIRE_BUFFER;
            }
            logger.log("INFO", "Baidu ASR access token refreshed");
        }
    }

    private String parseResponse(String response) {
        String errNo = JsonValueUtil.extractJsonValue(response, "err_no");
        if (errNo != null && !"0".equals(errNo)) {
            return null;
        }

        int resultIndex = response.indexOf("\"result\"");
        if (resultIndex == -1) {
            return null;
        }
        int arrayStart = response.indexOf("[", resultIndex);
        if (arrayStart == -1) {
            return null;
        }
        int arrayEnd = response.indexOf("]", arrayStart);
        if (arrayEnd == -1) {
            return null;
        }

        String arrayContent = response.substring(arrayStart + 1, arrayEnd).trim();
        if (arrayContent.startsWith("\"") && arrayContent.endsWith("\"")) {
            return arrayContent.substring(1, arrayContent.length() - 1);
        }
        return arrayContent;
    }

    private String sendPostRequest(String urlStr, String jsonBody) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private String sendGetRequest(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }
}
