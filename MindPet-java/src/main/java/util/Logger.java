package util;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class Logger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        System.out.printf("%s [%s] %s%n", timestamp, level, message);
    }
}
