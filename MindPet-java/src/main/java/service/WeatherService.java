package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.WeatherConfig;
import model.CityMapping;
import model.WeatherResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class WeatherService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String WTTR_BASE_URL = "https://wttr.in/";
    private static final String WTTR_COMPACT_FORMAT = "?format=%25l%7C%25C%7C%25t%7C%25f%7C%25h%7C%25w&lang=zh";
    private static final int MAX_WTTR_ATTEMPTS = 3;
    private static final long WTTR_RETRY_DELAY_MILLIS = 600L;

    private final Logger logger;
    private final WeatherConfig config;
    private int queryCount = 0;
    private int errorCount = 0;
    private long lastQueryTime = 0;

    @Autowired
    public WeatherService(WeatherConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public WeatherResponse getWeather(String input) {
        String city = CityMapping.getEnglishCityName(input);

        try {
            logger.log("INFO", "Weather query (wttr.in): " + input + " -> " + city);

            String urlStr = getBaseUrl()
                + encodePathSegment(city)
                + "?format=j1&lang=" + URLEncoder.encode(getLanguage(), StandardCharsets.UTF_8);

            for (int attempt = 1; attempt <= MAX_WTTR_ATTEMPTS; attempt++) {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            // 不设置 Accept header，避免 wttr.in CDN 节点对特定 Accept 返回缓存 500
            connection.setRequestProperty("User-Agent", "mindpet-weather-bot/1.0");

            int statusCode = connection.getResponseCode();
            queryCount++;
            lastQueryTime = System.currentTimeMillis();

            if (statusCode == 200) {
                String response = readResponse(connection);
                WeatherResponse weatherResponse = parseResponse(response, input);
                logger.log("INFO", "Weather query succeeded for " + city);
                connection.disconnect();
                return weatherResponse;
            }

            if (statusCode >= 500 && attempt < MAX_WTTR_ATTEMPTS) {
                connection.disconnect();
                logger.log("WARN", "wttr.in returned HTTP " + statusCode
                    + "; retrying " + (attempt + 1) + "/" + MAX_WTTR_ATTEMPTS + " for " + city);
                if (waitBeforeRetry(attempt)) {
                    continue;
                }
            }

            if (statusCode >= 500) {
                connection.disconnect();
                WeatherResponse compactResponse = getCompactWeather(city, input);
                if (compactResponse != null) {
                    logger.log("INFO", "Weather query succeeded via wttr.in compact fallback for " + city);
                    return compactResponse;
                }
            }

            errorCount++;
            if (statusCode == 404) {
                connection.disconnect();
                return WeatherResponse.createError("City not found: " + input);
            }
            connection.disconnect();
            logger.log("WARN", "wttr.in request failed for " + city + ", HTTP " + statusCode);
            return WeatherResponse.createError("Weather API request failed, status: " + statusCode);
            }

            errorCount++;
            return WeatherResponse.createError("Weather API request failed.");
        } catch (java.net.SocketTimeoutException e) {
            errorCount++;
            return WeatherResponse.createError("Weather request timed out.");
        } catch (java.net.UnknownHostException e) {
            errorCount++;
            return WeatherResponse.createError("Unable to resolve weather service host.");
        } catch (Exception e) {
            errorCount++;
            return WeatherResponse.createError("Weather query failed: " + e.getMessage());
        }
    }

    public int getQueryCount() {
        return queryCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public long getLastQueryTime() {
        return lastQueryTime;
    }

    private String readResponse(HttpURLConnection connection) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    /** wttr.in's compact endpoint uses a separate response path from format=j1. */
    private WeatherResponse getCompactWeather(String city, String fallbackCityName) {
        HttpURLConnection connection = null;
        try {
            String urlStr = getBaseUrl() + encodePathSegment(city) + WTTR_COMPACT_FORMAT;
            connection = (HttpURLConnection) new URL(urlStr).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "text/plain");
            connection.setRequestProperty("User-Agent", "mindpet-weather-bot/1.0");

            int statusCode = connection.getResponseCode();
            queryCount++;
            lastQueryTime = System.currentTimeMillis();
            if (statusCode != HttpURLConnection.HTTP_OK) {
                logger.log("WARN", "wttr.in compact fallback failed for " + city + ", HTTP " + statusCode);
                return null;
            }

            String[] fields = readResponse(connection).trim().split("\\|", -1);
            if (fields.length < 6 || fields[0].isBlank() || fields[1].isBlank()) {
                logger.log("WARN", "wttr.in compact fallback returned an invalid response for " + city);
                return null;
            }

            WeatherResponse response = new WeatherResponse();
            response.setCityName(fields[0].isBlank() ? fallbackCityName : fields[0]);
            response.setCountryCode("");
            response.setWeatherDescription(fields[1]);
            response.setTemperature(extractNumber(fields[2]));
            response.setFeelsLike(extractNumber(fields[3]));
            response.setHumidity((int) extractNumber(fields[4]));
            response.setWindSpeed(extractNumber(fields[5]) / 3.6);
            return response;
        } catch (Exception e) {
            logger.log("WARN", "wttr.in compact fallback failed for " + city + ": " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private double extractNumber(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("[-+]?\\d+(?:\\.\\d+)?")
            .matcher(value == null ? "" : value);
        return matcher.find() ? Double.parseDouble(matcher.group()) : 0.0;
    }

    private String getBaseUrl() {
        String configured = config.getApi().getBaseUrl();
        if (configured == null || configured.isBlank() || configured.contains("openweathermap.org")) {
            return WTTR_BASE_URL;
        }
        return configured.endsWith("/") ? configured : configured + "/";
    }

    private String getLanguage() {
        String configured = config.getApi().getLang();
        return configured == null || configured.isBlank() ? "zh" : configured;
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private boolean waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(WTTR_RETRY_DELAY_MILLIS * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private WeatherResponse parseResponse(String json, String fallbackCityName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode apiError = root.path("data").path("error");
            if (apiError.isArray() && !apiError.isEmpty()) {
                return WeatherResponse.createError(apiError.path(0).path("msg").asText("Weather service rejected the request."));
            }

            JsonNode current = root.path("current_condition").path(0);
            JsonNode nearestArea = root.path("nearest_area").path(0);
            if (current.isMissingNode() || nearestArea.isMissingNode()) {
                return WeatherResponse.createError("Failed to parse weather response.");
            }

            WeatherResponse response = new WeatherResponse();

            response.setCityName(firstValue(nearestArea.path("areaName"), fallbackCityName));
            response.setCountryCode(firstValue(nearestArea.path("country"), ""));
            response.setWeatherDescription(firstValue(current.path("weatherDesc"), null));
            response.setTemperature(current.path("temp_C").asDouble(0.0));
            response.setFeelsLike(current.path("FeelsLikeC").asDouble(0.0));
            response.setHumidity(current.path("humidity").asInt(0));
            response.setWindSpeed(current.path("windspeedKmph").asDouble(0.0) / 3.6);

            if (response.getCityName() == null || response.getWeatherDescription() == null) {
                return WeatherResponse.createError("Failed to parse weather response.");
            }

            return response;
        } catch (Exception e) {
            return WeatherResponse.createError("Failed to parse weather response.");
        }
    }

    private String firstValue(JsonNode values, String fallback) {
        if (values.isArray() && !values.isEmpty()) {
            String value = values.path(0).path("value").asText("");
            if (!value.isBlank()) return value;
        }
        return fallback;
    }
}
