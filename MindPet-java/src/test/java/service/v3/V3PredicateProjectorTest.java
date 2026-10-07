package service.v3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class V3PredicateProjectorTest {
    @Test
    void roleOrganizationAndFutureDestinationAreDistinctSemanticProjections() {
        assertThat(V3PredicateProjector.project("WORKS_AS","works_on").normalizedPredicate()).isEqualTo("experienced");
        assertThat(V3PredicateProjector.project("WORKS_AT","works_on").normalizedPredicate()).isEqualTo("belongs_to");
        assertThat(V3PredicateProjector.project("MOVES_TO","related_to","FUTURE").normalizedPredicate()).isEqualTo("plans");
        assertThat(V3PredicateProjector.project("MOVES_TO","related_to","CURRENT").normalizedPredicate()).isEqualTo("related_to");
        assertThat(V3PredicateProjector.project("DRIVES_TO_WORK","uses","CURRENT").normalizedPredicate()).isEqualTo("uses");
    }

    @Test
    void stoppedUsingVariantIsRetractionOnlyWithExplicitCurrentCessation() {
        assertThat(V3FactEventJournal.supportsRetraction("I stopped using the previous device.","STOPPED_USING")).isTrue();
        assertThat(V3FactEventJournal.supportsRetraction("If I stopped using the previous device?","STOPPED_USING")).isFalse();
    }
    @Test
    void activityUsingInstrumentIsUseWithoutLosingRichPredicate() {
        for (String rich : new String[]{"WRITES_CODE_WITH", "PROGRAMS_USING", "DEVELOPS_WITH"}) {
            var result = V3PredicateProjector.project(rich, "works_on");
            assertThat(result.normalizedPredicate()).isEqualTo("uses");
            assertThat(result.semanticPredicate()).isEqualTo(rich);
        }
    }

    @Test
    void activityOutputIsNotConfusedWithInstrument() {
        assertThat(V3PredicateProjector.project("DEVELOPS", "builds").normalizedPredicate()).isEqualTo("builds");
        assertThat(V3PredicateProjector.project("WORKS_ON", "works_on").normalizedPredicate()).isEqualTo("works_on");
        assertThat(V3PredicateProjector.project("DRIVES_TO_WORK", "uses").normalizedPredicate()).isEqualTo("uses");
    }

    @Test
    void userParticipantCannotBeMistakenForUseAction() {
        assertThat(V3PredicateProjector.project("BELONGS_TO_USER", "belongs_to").normalizedPredicate()).isEqualTo("belongs_to");
        assertThat(V3PredicateProjector.project("OWNED_BY_USER", "belongs_to").normalizedPredicate()).isEqualTo("belongs_to");
        assertThat(V3PredicateProjector.project("USES_FOR_STUDY", "learns").normalizedPredicate()).isEqualTo("uses");
        assertThat(V3PredicateProjector.project("USES_FOR_GOAL_PLANNING", "plans").normalizedPredicate()).isEqualTo("uses");
    }
}
