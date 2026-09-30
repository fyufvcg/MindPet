package service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KnowledgeGraphPromptContractTest {

    private static final String PROMPT = extractionPrompt();
    private static final String V1_SHA256 = "a2f27c59eb39499dc6682bb7e927afc0e19f87013559c0aeacf3c2ef8cb002c9";
    private static final String V2_SHA256 = "cafa86f6e08703a60f236f4f19b371c3a79df917e74133aacce2377ee06e627e";
    private static final String V21_SHA256 = "1e02c1b13dbb1edfe0984ade5eaa5a3f96ee7da765b9d853abce71f1ddfeb649";
    private static final String PROMPT_SHA256 = sha256(PROMPT);

    @Test
    void recognizedPromptHashHasMatchingVersionContract() {
        assertThat(PROMPT_SHA256).isIn(V1_SHA256, V2_SHA256, V21_SHA256);
        if (V1_SHA256.equals(PROMPT_SHA256)) {
            assertThat(PROMPT)
                .contains("durable knowledge graph")
                .contains("stable preferences, active projects, goals")
                .contains("shouldRemember must be false for small talk, one-off tasks")
                .contains("If nothing is durable");
        } else {
            assertThat(PROMPT)
                .contains("TASK A - LONG-TERM MEMORY DECISION")
                .contains("TASK B - KNOWLEDGE GRAPH EXTRACTION")
                .contains("extraction is independent from long-term-memory persistence")
                .contains("explicit short-term end");
        }
    }

    @Test
    void stableLongTermPreferenceAllowsLtmAndKnowledgeGraph() {
        assumeV2();
        assertThat(PROMPT)
            .contains("TASK A - LONG-TERM MEMORY DECISION")
            .contains("persistent preferences")
            .contains("TASK B - KNOWLEDGE GRAPH EXTRACTION");
    }

    @Test
    void oneOffTaskRejectsLtmWithoutSuppressingKnowledgeGraph() {
        assumeV2();
        assertThat(PROMPT)
            .contains("one-off tasks")
            .contains("Even when worthRemembering is false")
            .contains("still output valid entities and relations");
    }

    @Test
    void shortTermExplicitEventCanProduceEventEntity() {
        assumeV2();
        assertThat(PROMPT)
            .contains("Short-term but explicit information may therefore produce KG output")
            .contains("Use event for a specific event that happened or will happen");
    }

    @Test
    void boundedMultiWeekScheduleIsNotAutomaticallyDurableButCanProduceKg() {
        assumeV2();
        assertThat(PROMPT)
            .contains("several days or weeks")
            .contains("explicit short-term end")
            .contains("is not durable long-term memory solely because it repeats")
            .contains("temporary schedule");
    }

    @Test
    void ordinarySmallTalkCanLeaveBothTasksEmpty() {
        assumeV2();
        assertThat(PROMPT)
            .contains("ordinary small talk")
            .contains("Return empty entities and relations only when Task B also finds no valid structured fact")
            .contains("If neither task finds anything");
    }

    @Test
    void jsonSchemaEntityTypesAndPredicatesRemainUnchanged() {
        assertThat(PROMPT)
            .contains("\"worthRemembering\": true")
            .contains("\"memory\": {")
            .contains("\"entities\": [")
            .contains("\"relations\": [")
            .contains("project|technology|tool|preference|goal|person|topic|organization|place|event|other")
            .contains("prefers|dislikes|uses|learns|builds|works_on|plans|knows|experienced|belongs_to|related_to");
        if (isV2Family()) {
            assertThat(PROMPT)
                .contains("Use related_to only when no more specific allowed predicate applies");
        }
    }

    private static String extractionPrompt() {
        try {
            Field field = KnowledgeGraphService.class.getDeclaredField("EXTRACTION_PROMPT");
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot read production extraction prompt", exception);
        }
    }

    private static void assumeV2() {
        assumeTrue(isV2Family(), "V2-family contract assertions");
    }

    private static boolean isV2Family() {
        return V2_SHA256.equals(PROMPT_SHA256) || V21_SHA256.equals(PROMPT_SHA256);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte valueByte : bytes) result.append(String.format("%02x", valueByte));
            return result.toString();
        } catch (Exception exception) {
            throw new AssertionError("Cannot hash production extraction prompt", exception);
        }
    }
}
