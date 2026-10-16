package config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only non-secret metadata belongs in Java's compatibility configuration files. */
final class RuntimeConfigFile {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path path;
    RuntimeConfigFile(Path path) { this.path = path; }
    Map<String, String> read() throws IOException {
        if (!Files.exists(path)) return Map.of();
        return MAPPER.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, String>>() {});
    }
    void write(Map<String, String> metadata) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), ".model-config-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), metadata);
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
