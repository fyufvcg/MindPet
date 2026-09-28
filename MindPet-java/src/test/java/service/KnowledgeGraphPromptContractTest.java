package service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphPromptContractTest {

    private static final String PROMPT = extractionPrompt();

    @Test
    void stableLongTermPreferenceAllowsLtmAndKnowledgeGraph() {
        assertThat(PROMPT)
            .contains("TASK A - LONG-TERM MEMORY DECISION")
            .contains("persistent preferences")
            .contains("TASK B - KNOWLEDGE GRAPH EXTRACTION");
    }

    @Test
    void oneOffTaskRejectsLtmWithoutSuppressingKnowledgeGraph() {
        assertThat(PROMPT)
            .contains("one-off tasks")
            .contains("Even when worthRemembering is false")
            .contains("still output valid entities and relations");
    }

    @Test
    void shortTermExplicitEventCanProduceEventEntity() {
        assertThat(PROMPT)
            .contains("Short-term but explicit information may therefore produce KG output")
            .contains("Use event for a specific event that happened or will happen");
    }

    @Test
    void boundedMultiWeekScheduleIsNotAutomaticallyDurableButCanProduceKg() {
        assertThat(PROMPT)
            .contains("several days or weeks")
            .contains("explicit short-term end")
            .contains("is not durable long-term memory solely because it repeats")
            .contains("temporary schedule");
    }

    @Test
    void ordinarySmallTalkCanLeaveBothTasksEmpty() {
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
            .contains("prefers|dislikes|uses|learns|builds|works_on|plans|knows|experienced|belongs_to|related_to")
            .contains("Use related_to only when no more specific allowed predicate applies");
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
}
