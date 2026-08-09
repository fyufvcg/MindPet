package model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 天气响应数据模型
 */
public class WeatherResponse {
    
    private String cityName;
    private String countryCode;
    private String weatherDescription;
    private double temperature;
    private double feelsLike;
    private int humidity;
    private double windSpeed;
    private String errorMessage;
    private LocalDateTime queryTime;
    
    public WeatherResponse() {
        this.queryTime = LocalDateTime.now();
    }
    
    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }
    
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    
    public String getWeatherDescription() { return weatherDescription; }
    public void setWeatherDescription(String weatherDescription) { this.weatherDescription = weatherDescription; }
    
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    
    public double getFeelsLike() { return feelsLike; }
    public void setFeelsLike(double feelsLike) { this.feelsLike = feelsLike; }
    
    public int getHumidity() { return humidity; }
    public void setHumidity(int humidity) { this.humidity = humidity; }
    
    public double getWindSpeed() { return windSpeed; }
    public void setWindSpeed(double windSpeed) { this.windSpeed = windSpeed; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public String getQueryTimeFormatted() {
        return queryTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }
    
    public static WeatherResponse createError(String message) {
        WeatherResponse response = new WeatherResponse();
        response.setErrorMessage(message);
        return response;
    }
}