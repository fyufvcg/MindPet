package com.weather.wechatbot;

import config.BaiduConfig;
import config.EmailConfig;
import config.LlmConfig;
import config.TencentMapConfig;
import config.WeatherConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {"com.weather.wechatbot", "service", "config", "tool", "tool.impl", "util", "model", "controller"}
)
@EnableConfigurationProperties({LlmConfig.class, WeatherConfig.class, BaiduConfig.class, TencentMapConfig.class, EmailConfig.class})
@EnableScheduling
public class WechatBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(WechatBotApplication.class, args);
    }
}
