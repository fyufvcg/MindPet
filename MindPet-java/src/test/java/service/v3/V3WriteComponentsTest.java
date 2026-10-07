package service.v3;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class V3WriteComponentsTest {

    @Test
    void canonicalizationIsDeterministicAndConsolidatesAliases() {
        List<V3EntityCanonicalizer.Entity> first = V3EntityCanonicalizer.canonicalize(List.of(
            new V3EntityCanonicalizer.Input(
                "Postgres", "PostgreSQL", "technology", "database", .7,
                List.of("PG"), true),
            new V3EntityCanonicalizer.Input(
                "PostgreSQL", "PostgreSQL", "technology", "primary database", .9,
                List.of("Postgres"), true),
            new V3EntityCanonicalizer.Input(
                "the system", "the system", "other", "generic", .5,
                List.of(), false)), 8);
        List<V3EntityCanonicalizer.Entity> second = V3EntityCanonicalizer.canonicalize(List.of(
            new V3EntityCanonicalizer.Input(
                "Postgres", "PostgreSQL", "technology", "database", .7,
                List.of("PG"), true),
            new V3EntityCanonicalizer.Input(
                "PostgreSQL", "PostgreSQL", "technology", "primary database", .9,
                List.of("Postgres"), true)), 8);

        assertThat(first).isEqualTo(second).hasSize(1);
        assertThat(first.get(0).canonicalName()).isEqualTo("PostgreSQL");
        assertThat(first.get(0).aliases()).containsExactly("Postgres", "PG");
        assertThat(first.get(0).importance()).isEqualTo(.9);
    }

    @Test
    void descriptiveEntitiesUseConciseTypeAwareCanonicalNamesAndKeepAliases() {
        List<V3EntityCanonicalizer.Entity> entities = V3EntityCanonicalizer.canonicalize(List.of(
            new V3EntityCanonicalizer.Input(
                "先看摘要再看正文", "先看摘要再看正文", "preference", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "阅读偏好", "阅读偏好(先摘要后正文)", "preference", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "回答方式", "先摘要后正文的回答方式", "preference", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "选购偏好", "电子产品性能优先偏好", "preference", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "偏好", "性能", "preference", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "三年内完成一项研究", "三年内完成一项研究", "goal", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "未来两年专注工作", "专注工作", "goal", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "每天练习三十分钟日语口语", "每天练习三十分钟日语口语", "goal", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "考到职业资格证书", "考到职业资格证书", "goal", "", .8,
                List.of(), true),
            new V3EntityCanonicalizer.Input(
                "明天九点去参加一次会议", "明天九点去参加一次会议", "event", "", .8,
                List.of(), true)), 8);

        assertThat(entities).extracting(V3EntityCanonicalizer.Entity::canonicalName)
            .containsExactly(
                "先摘要后正文", "性能优先", "完成研究", "未来两年专注工作",
                "日语口语练习", "考取职业资格证书", "明天参加会议");
        assertThat(entities.get(0).aliases()).contains(
            "先看摘要再看正文", "阅读偏好", "阅读偏好(先摘要后正文)");
    }

    @Test
    void richPredicatesProjectToStableMindPetContract() {
        assertThat(V3PredicateProjector.project("DRIVES_TO_WORK", "").normalizedPredicate())
            .isEqualTo("uses");
        assertThat(V3PredicateProjector.project("USES_FOR_DEVELOPMENT", "uses").normalizedPredicate())
            .isEqualTo("uses");
        assertThat(V3PredicateProjector.project("MEMBER_OF", "").normalizedPredicate())
            .isEqualTo("belongs_to");
        assertThat(V3PredicateProjector.project("LIVES_IN", "belongs_to").normalizedPredicate())
            .isEqualTo("related_to");
        assertThat(V3PredicateProjector.project("曾用 Python 但当前不再使用", "uses").normalizedPredicate())
            .isEqualTo("experienced");
        assertThat(V3PredicateProjector.project("持续制作个人作品集网站", "works_on").normalizedPredicate())
            .isEqualTo("builds");
        assertThat(V3PredicateProjector.project("FOCUSES_ON_WORK_FOR_NEXT_TWO_YEARS", "works_on")
            .normalizedPredicate()).isEqualTo("plans");
    }

    @Test
    void temporaryStateNeverBecomesPermanent() {
        V3FactResolver.Resolution resolution = V3FactResolver.resolve(
            "SUPERSEDES", "TEMPORARY", "2026-10-01T00:00:00Z", "");
        assertThat(resolution.action()).isEqualTo("SUPERSEDES");
        assertThat(resolution.temporalStatus()).isEqualTo("TEMPORARY");
        assertThat(resolution.validFrom()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(resolution.validTo()).isEmpty();
    }

    @Test
    void sequencePreferenceFormattingDoesNotCreateDifferentIdentity() {
        assertThat(V3EntityCanonicalizer.canonicalName("先摘要,后细节", "preference")).isEqualTo("先摘要后细节");
        assertThat(V3EntityCanonicalizer.canonicalName("先摘要，后细节的回答风格", "preference")).isEqualTo("先摘要后细节");
        assertThat(V3EntityCanonicalizer.canonicalName("先给摘要再补充细节", "preference")).isEqualTo("先摘要后细节");
    }
}
