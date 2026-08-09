package service;

import config.BaiduConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import util.JsonValueUtil;
import util.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class BaiduTTSService {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String TTS_URL = "https://tsn.baidu.com/text2audio";
    private static final long TOKEN_EXPIRE_BUFFER = 5 * 60 * 1000;

    public static final Map<Integer, String> VOICE_MAP = new HashMap<>();
    static {
        VOICE_MAP.put(0, "Standard female");
        VOICE_MAP.put(1, "Standard male");
        VOICE_MAP.put(3, "Emotional male");
        VOICE_MAP.put(4, "Emotional female");
        VOICE_MAP.put(10, "Chinese female");
        VOICE_MAP.put(11, "Chinese male");
    }

    private final Logger logger;
    private final BaiduConfig config;
    private String accessToken;
    private long tokenExpireTime = 0;
    private int person;

    @Autowired
    public BaiduTTSService(BaiduConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.person = config.getSpeech().getTts().getPerson();
        logger.log("INFO", "Baidu TTS service initialized, voice=" + person);
    }

    public boolean isConfigured() {
        return config.getSpeech().getApi().getKey() != null && !config.getSpeech().getApi().getKey().isEmpty()
            && config.getSpeech().getApi().getSecret() != null && !config.getSpeech().getApi().getSecret().isEmpty();
    }

    public boolean setPerson(int person) {
        if (VOICE_MAP.containsKey(person)) {
            this.person = person;
            logger.log("INFO", "Voice switched to " + VOICE_MAP.get(person));
            return true;
        }
        return false;
    }

    public String getCurrentVoiceName() {
        return VOICE_MAP.getOrDefault(person, "Unknown");
    }

    public String getAvailableVoices() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> entry : VOICE_MAP.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
        }
        return sb.toString();
    }

    public byte[] synthesize(String text) {
        if (!isConfigured() || text == null || text.trim().isEmpty()) {
            return null;
        }

        try {
            if (accessToken == null || System.currentTimeMillis() > tokenExpireTime) {
                refreshToken();
            }
            if (accessToken == null) {
                return null;
            }

            if (text.length() > 300) {
                text = text.substring(0, 300);
            }

            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            BaiduConfig.Speech.Tts tts = config.getSpeech().getTts();
            String params = "tex=" + encodedText
                + "&tok=" + accessToken
                + "&cuid=weather-bot&ctp=1&lan=zh"
                + "&spd=" + tts.getSpeed()
                + "&pit=" + tts.getPitch()
                + "&vol=" + tts.getVolume()
                + "&per=" + person
                + "&aue=3";

            return sendPostRequestBinary(TTS_URL, params);
        } catch (Exception e) {
            logger.log("ERROR", "TTS synthesis failed: " + e.getMessage());
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
            logger.log("INFO", "Baidu TTS access token refreshed");
        }
    }

    private byte[] sendPostRequestBinary(String urlStr, String params) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(params.getBytes(StandardCharsets.UTF_8));
        }

        String contentType = conn.getContentType();
        if (contentType != null && contentType.contains("json")) {
            StringBuilder error = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
            }
            logger.log("ERROR", "TTS returned JSON error: " + error);
            return null;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }
        return baos.toByteArray();
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
