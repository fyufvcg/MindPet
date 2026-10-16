package config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ModelRuntimeConfigTest {
    @TempDir Path root;
    @Test void llmPersistsMetadataWithoutCredentialAndExplicitClearBlocksStaticFallback() throws Exception {
        Path path = root.resolve("llm.json");
        DynamicLlmConfig config = new DynamicLlmConfig(path);
        config.update("fixture-secret", "https://example.com/v1", "model-a", "prompt");
        assertFalse(Files.readString(path).contains("fixture-secret"));
        assertFalse(Files.readString(path).contains("apiKey"));
        assertEquals("fixture-secret", config.effectiveApiKey("static-secret"));
        config.update("", "https://other.example/v1", "model-b", "");
        assertEquals("", config.effectiveApiKey("static-secret"));
        assertTrue(config.hasOverride());
        DynamicLlmConfig restarted = new DynamicLlmConfig(path);
        restarted.loadFromFile();
        assertEquals("", restarted.getApiKey());
    }
    @Test void legacyLlmKeyIsRemovedFromDiskOnLoad() throws Exception {
        Path path = root.resolve("llm.json");
        Files.writeString(path, "{\"apiKey\":\"legacy-fixture\",\"baseUrl\":\"https://example.com\",\"model\":\"m\"}");
        DynamicLlmConfig config = new DynamicLlmConfig(path);
        config.loadFromFile();
        assertEquals("legacy-fixture", config.getApiKey());
        assertFalse(Files.readString(path).contains("legacy-fixture"));
        assertEquals("https://example.com", config.snapshot().baseUrl());
    }
    @Test void embeddingNeverPersistsKeyAndClearsItWithFullSnapshot() throws Exception {
        Path path = root.resolve("embedding.json");
        EmbeddingConfig config = new EmbeddingConfig(path);
        config.update("DOUBAO", "fixture-secret", "https://example.com/embed", "model");
        assertFalse(Files.readString(path).contains("fixture-secret"));
        assertFalse(Files.readString(path).contains("doubaoApiKey"));
        config.update("DOUBAO", "", "https://example.com/embed", "model");
        assertFalse(config.hasDoubaoApiKey());
        assertTrue(config.hasOverride());
    }
    @Test void legacyEmbeddingKeyIsRemovedFromDiskOnLoad() throws Exception {
        Path path = root.resolve("embedding.json");
        Files.writeString(path, "{\"mode\":\"DOUBAO\",\"doubaoApiKey\":\"legacy-fixture\"}");
        EmbeddingConfig config = new EmbeddingConfig(path);
        config.loadFromFile();
        assertTrue(config.hasDoubaoApiKey());
        assertFalse(Files.readString(path).contains("legacy-fixture"));
        EmbeddingConfig restarted = new EmbeddingConfig(path);
        restarted.loadFromFile();
        assertFalse(restarted.hasDoubaoApiKey());
    }
}
