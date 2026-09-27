package config;

import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class SqliteNative {
    private SqliteNative() {}

    public static Path resolveExtension(String configuredPath) throws Exception {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path path = Path.of(configuredPath).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String platform;
        String file;
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win") && !arm) { platform = "windows-x86_64"; file = "vec0.dll"; }
        else if (os.contains("mac")) { platform = arm ? "macos-aarch64" : "macos-x86_64"; file = "vec0.dylib"; }
        else if (os.contains("linux")) { platform = arm ? "linux-aarch64" : "linux-x86_64"; file = "vec0.so"; }
        else return null;
        ClassPathResource resource = new ClassPathResource("native/sqlite-vec/" + platform + "/" + file);
        if (!resource.exists()) return null;
        Path target = Path.of(System.getProperty("java.io.tmpdir"), "mindpet", "sqlite-vec-0.1.9", platform, file);
        Files.createDirectories(target.getParent());
        try (var input = resource.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public static String sqlPath(Path path) {
        return path.toString().replace("'", "''").replace('\\', '/');
    }
}
