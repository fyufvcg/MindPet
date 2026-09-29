package experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import service.TemporalNormalizer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic smoke replay for the time portion of the experiment fixture. */
class MemoryCuratorReplayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixtureReplayKeepsTemporalExpectationsStable() throws Exception {
        int checked = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("memory/curator-fixtures.jsonl").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode sample = mapper.readTree(line);
                JsonNode expected = sample.get("expected_time");
                if (expected == null) continue;
                var result = TemporalNormalizer.resolve(
                    expected.path("raw").asText(),
                    Instant.parse(sample.path("occurred_at").asText()),
                    ZoneId.of(sample.path("timezone").asText()));
                assertThat(result.status()).isEqualTo(expected.path("status").asText());
                if (expected.has("normalized_start")) {
                    assertThat(result.normalizedStart()).isEqualTo(expected.path("normalized_start").asText());
                }
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(2);
    }
}
