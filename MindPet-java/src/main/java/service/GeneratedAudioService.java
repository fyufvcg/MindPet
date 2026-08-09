package service;

import org.springframework.stereotype.Service;
import util.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

/** Stores short-lived TTS artifacts for the local desktop client. */
@Service
public class GeneratedAudioService {

    private static final long RETENTION_MILLIS = Duration.ofDays(7).toMillis();

    private final Logger logger;
    private final Path outputDirectory = Path.of("generated_audio").toAbsolutePath().normalize();

    public GeneratedAudioService(Logger logger) {
        this.logger = logger;
    }

    public synchronized AudioArtifact saveMp3(byte[] audioData) throws IOException {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("Audio data is empty");
        }

        Files.createDirectories(outputDirectory);
        removeExpiredFiles();

        String fileName = "voice-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".mp3";
        Path target = outputDirectory.resolve(fileName).normalize();
        Path temporary = outputDirectory.resolve(fileName + ".tmp").normalize();
        if (!target.startsWith(outputDirectory) || !temporary.startsWith(outputDirectory)) {
            throw new IOException("Invalid generated audio path");
        }

        Files.write(temporary, audioData, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        return new AudioArtifact(target, fileName);
    }

    private void removeExpiredFiles() {
        long cutoff = System.currentTimeMillis() - RETENTION_MILLIS;
        try (Stream<Path> files = Files.list(outputDirectory)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    logger.log("WARN", "Unable to remove expired audio artifact: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.log("WARN", "Unable to scan generated audio artifacts: " + e.getMessage());
        }
    }

    public record AudioArtifact(Path path, String fileName) {}
}
