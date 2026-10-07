package service.v3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class V3ReferenceResolutionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyKnownHistoricalBindingsAndExplicitDistinctCurrentPeople() throws Exception {
        List<V3ReferenceResolution.Candidate> candidates = List.of(
            new V3ReferenceResolution.Candidate("p1", "Project Aurora", "project", "long-term project"));

        V3ReferenceResolution.Plan plan = V3ReferenceResolution.parse(mapper, """
            {"bindings":[{"surface":"这个项目","candidateId":"p1"},
                         {"surface":"无关","candidateId":"invented"}],
             "currentEntities":[
               {"surface":"一个王浩","canonicalName":"王浩(后端)","type":"person"},
               {"surface":"另一个王浩","canonicalName":"王浩(视觉设计)","type":"person"}],
             "contextOnlyTerms":["后端","视觉设计"]}
            """, "这个项目属于 Acme Research。我的两个同事都叫王浩：一个王浩做后端，另一个王浩做视觉设计，他们是两个人。",
            candidates);

        assertThat(plan.bindings()).hasSize(1);
        assertThat(plan.rewriteEndpoint("这个项目")).isEqualTo("Project Aurora");
        assertThat(plan.currentEntities()).extracting(V3ReferenceResolution.CurrentEntity::canonicalName)
            .containsExactly("王浩(后端)", "王浩(视觉设计)");
        assertThat(plan.suppresses("后端")).isTrue();
        assertThat(plan.suppresses("Project Aurora")).isFalse();
    }

    @Test
    void onlyTriggersForReferenceOrExplicitSameNameSignals() {
        assertThat(V3ReferenceResolution.needsResolution("I prefer tea.")).isFalse();
        assertThat(V3ReferenceResolution.needsResolution("这个项目属于实验室。 ")).isTrue();
        assertThat(V3ReferenceResolution.needsResolution("两个同事同名，但他们是两个人。")).isTrue();
    }

    @Test
    void derivesStableLabelsOnlyFromExplicitRelationshipAndNameSyntax() throws Exception {
        V3ReferenceResolution.Plan plan = V3ReferenceResolution.parse(mapper,
            "{\"bindings\":[],\"currentEntities\":[],\"contextOnlyTerms\":[]}",
            "我公司有个同事叫王浩，他负责后端。我邻居也叫李明。", List.of());

        assertThat(plan.currentEntities()).extracting(V3ReferenceResolution.CurrentEntity::canonicalName)
            .containsExactlyInAnyOrder("王浩(公司同事)", "李明(邻居)");
        assertThat(plan.rewriteCurrentCanonical("王浩")).isEqualTo("王浩(公司同事)");
    }
}
