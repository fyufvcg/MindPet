package service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalNormalizerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void resolvesRelativeDateAgainstMessageTime() {
        var result = TemporalNormalizer.resolve("后天",
            Instant.parse("2026-09-22T02:00:00Z"), SHANGHAI);

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.normalizedStart()).isEqualTo("2026-09-24");
        assertThat(result.anchor()).isEqualTo("message_occurred_at");
    }

    @Test
    void processingLaterDoesNotMoveTheResolvedDate() {
        var original = TemporalNormalizer.resolve("后天",
            Instant.parse("2026-09-22T02:00:00Z"), SHANGHAI);
        var retry = TemporalNormalizer.resolve("后天",
            Instant.parse("2026-09-22T02:00:00Z"), SHANGHAI);

        assertThat(retry.normalizedStart()).isEqualTo(original.normalizedStart());
        assertThat(retry.normalizedStart()).isNotEqualTo("2026-09-27");
    }

    @Test
    void keepsAmbiguousRangesUnresolved() {
        var result = TemporalNormalizer.resolve("过几天",
            Instant.parse("2026-09-22T02:00:00Z"), SHANGHAI);

        assertThat(result.status()).isEqualTo("ambiguous");
        assertThat(result.normalizedStart()).isEmpty();
    }

    @Test
    void resolvesExplicitCrossYearDate() {
        var result = TemporalNormalizer.resolve("明天",
            Instant.parse("2026-12-31T02:00:00Z"), SHANGHAI);

        assertThat(result.normalizedStart()).isEqualTo("2027-01-01");
    }
}
