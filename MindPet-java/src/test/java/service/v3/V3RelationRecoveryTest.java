package service.v3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class V3RelationRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<V3RelationRecovery.Endpoint> endpoints = List.of(
        new V3RelationRecovery.Endpoint("user", "person", List.of()),
        new V3RelationRecovery.Endpoint("Project Beacon", "project", List.of("这个项目")),
        new V3RelationRecovery.Endpoint("North Lab", "organization", List.of()));

    @Test void recoversExplicitCurrentEntityRelationWhenPrimaryIsEmpty() throws Exception {
        String message = "这个项目属于 North Lab。";
        assertThat(V3RelationRecovery.shouldRecover(message, 0, endpoints)).isTrue();
        var relations = V3RelationRecovery.validate(mapper, reply("Project Beacon", "North Lab", message),
            message, endpoints, new ArrayList<>());
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).path("source").asText()).isEqualTo("Project Beacon");
        assertThat(relations.get(0).path("semanticPredicate").asText()).isEqualTo("BELONGS_TO");
    }

    @Test void identityHintsDoNotMakeHistoryEvidence() throws Exception {
        String current = "这个项目我还记得。";
        assertThat(V3RelationRecovery.shouldRecover(current, 0, endpoints)).isFalse();
        var reasons = new ArrayList<String>();
        assertThat(V3RelationRecovery.validate(mapper,
            reply("Project Beacon", "North Lab", "这个项目属于 North Lab。"), current, endpoints, reasons)).isEmpty();
        assertThat(reasons).contains("RELATION_RECOVERY_EVIDENCE_REJECTED");
    }

    @Test void rejectsHypothesesQuestionsAndUndecidedChoices() {
        for (String message : List.of("如果这个项目属于 North Lab。", "假设这个项目属于 North Lab。",
                "这个项目可能属于 North Lab。", "我在 North Lab 和 Project Beacon 之间考虑。",
                "这个项目是否属于 North Lab？")) {
            assertThat(V3RelationRecovery.shouldRecover(message, 0, endpoints)).as(message).isFalse();
        }
    }

    @Test void rejectsEndpointsOutsideClosedWorld() throws Exception {
        String message = "这个项目属于 North Lab。";
        var reasons = new ArrayList<String>();
        assertThat(V3RelationRecovery.validate(mapper, reply("Invented Project", "North Lab", message),
            message, endpoints, reasons)).isEmpty();
        assertThat(reasons).contains("RELATION_RECOVERY_ENDPOINT_REJECTED");
    }

    @Test void skipsRecoveryWhenPrimaryAlreadyHasRelation() {
        assertThat(V3RelationRecovery.shouldRecover("这个项目属于 North Lab。", 1, endpoints)).isFalse();
    }

    @Test void specificPredicatesProjectWithoutDuplicateNormalizedRelation() throws Exception {
        String message = "我每天驾驶 Roadster 上班。";
        var universe = List.of(endpoints.get(0), new V3RelationRecovery.Endpoint("Roadster", "tool", List.of()));
        String raw = """
            {"relations":[
              {"source":"user","target":"Roadster","semanticPredicate":"DRIVES_TO_WORK",
               "predicate":"uses","evidence":"我每天驾驶 Roadster 上班。"},
              {"source":"user","target":"Roadster","semanticPredicate":"DRIVES_TO_WORK_DAILY",
               "predicate":"uses","evidence":"我每天驾驶 Roadster 上班。"}]}
            """;
        var reasons = new ArrayList<String>();
        var relations = V3RelationRecovery.validate(mapper, raw, message, universe, reasons);
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).path("semanticPredicate").asText()).isEqualTo("DRIVES_TO_WORK");
        assertThat(V3PredicateProjector.project(relations.get(0).path("semanticPredicate").asText(), "uses")
            .normalizedPredicate()).isEqualTo("uses");
        assertThat(reasons).contains("RELATION_RECOVERY_DUPLICATE_REJECTED");
    }

    @Test void typedReferenceRequiresUniqueHistoricalIdentity() {
        var a = new V3ReferenceResolution.Candidate("id-a", "Project Beacon", "project", "");
        var b = new V3ReferenceResolution.Candidate("id-b", "Project Cedar", "project", "");
        assertThat(V3ReferenceResolution.completeTypedReferences(V3ReferenceResolution.Plan.empty(),
            "这个项目属于 North Lab。", List.of(a)).bindings()).hasSize(1);
        assertThat(V3ReferenceResolution.completeTypedReferences(V3ReferenceResolution.Plan.empty(),
            "这个项目属于 North Lab。", List.of(a, b)).bindings()).isEmpty();
    }

    @Test void demonstrativeTimeWindowsDoNotTriggerEntityResolution() {
        assertThat(V3ReferenceResolution.needsResolution("这个季度我负责数据库迁移项目。")).isFalse();
        assertThat(V3ReferenceResolution.needsResolution("This quarter I work on Project Cedar.")).isFalse();
        assertThat(V3ReferenceResolution.needsResolution("这个项目属于 North Lab。")).isTrue();
        assertThat(V3ReferenceResolution.needsResolution("这个季度我和他一起负责数据库迁移项目。")).isTrue();
    }

    private String reply(String source, String target, String evidence) throws Exception {
        var root = mapper.createObjectNode();
        var relation = root.putArray("relations").addObject();
        relation.put("source", source).put("target", target).put("semanticPredicate", "BELONGS_TO")
            .put("predicate", "belongs_to").put("evidence", evidence).put("confidence", .95).put("importance", .7);
        return mapper.writeValueAsString(root);
    }
}
