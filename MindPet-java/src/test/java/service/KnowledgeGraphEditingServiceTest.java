package service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import controller.KnowledgeGraphController;
import util.Logger;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeGraphEditingServiceTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    EmbeddingService embeddings;
    KnowledgeGraphEditingService editing;

    @BeforeEach void setup() {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + directory.resolve("graph.db").toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 0});
        var target = new KnowledgeGraphEditingService(jdbc, embeddings);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        editing = (KnowledgeGraphEditingService) proxy.getProxy();
    }

    String node(String user, String name) {
        return (String) editing.saveEntity(user, null,
            new KnowledgeGraphEditingService.EntityInput(name, "topic", "明确说明", 0.7)).get("id");
    }

    @Test void entityEditsKeepIdentityLinksAndUpdateVectorWithoutIncrementingMentions() {
        String left = node("u", "摄影"), right = node("u", "课程");
        String relation = (String) editing.saveRelation("u", null,
            new KnowledgeGraphEditingService.RelationInput(left, right, "learns", 0.7)).get("id");
        when(embeddings.embed("人像摄影 新说明")).thenReturn(new float[]{0, 1});
        editing.saveEntity("u", left,
            new KnowledgeGraphEditingService.EntityInput("人像摄影", "project", "新说明", 0.8));
        assertThat(jdbc.queryForObject("SELECT display_name FROM kg_entity WHERE id=?", String.class, left)).isEqualTo("人像摄影");
        assertThat(jdbc.queryForObject("SELECT mention_count FROM kg_entity WHERE id=?", Integer.class, left)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_entity_id FROM kg_relation WHERE id=?", String.class, relation)).isEqualTo(left);
        assertThat(VectorSearchService.decode(jdbc.queryForObject("SELECT embedding FROM kg_entity WHERE id=?", byte[].class, left))).containsExactly(0, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kg_evidence WHERE entity_id=?", Integer.class, left)).isEqualTo(2);
        // Unavailable embedding clears the old vector; explicit editing still persists.
        when(embeddings.embed("摄影笔记 ")).thenReturn(null);
        editing.saveEntity("u", left, new KnowledgeGraphEditingService.EntityInput("摄影笔记", "topic", "", 0.7));
        assertThat(jdbc.queryForObject("SELECT embedding FROM kg_entity WHERE id=?", byte[].class, left)).isNull();
    }

    @Test void rejectsDuplicatesSelfLinksAndCrossUserChanges() {
        String a = node("u", "摄影"), b = node("u", "课程"), foreign = node("other", "课程");
        assertThatThrownBy(() -> node("u", " 摄影 ")).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> editing.saveEntity("other", a,
            new KnowledgeGraphEditingService.EntityInput("改名", "topic", "", 0.5))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> editing.saveRelation("u", null,
            new KnowledgeGraphEditingService.RelationInput(a, a, "related_to", 0.7))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> editing.saveRelation("u", null,
            new KnowledgeGraphEditingService.RelationInput(a, foreign, "related_to", 0.7))).isInstanceOf(ResponseStatusException.class);
        editing.saveRelation("u", null, new KnowledgeGraphEditingService.RelationInput(a, b, "learns", 0.7));
        assertThatThrownBy(() -> editing.saveRelation("u", null,
            new KnowledgeGraphEditingService.RelationInput(a, b, "learns", 0.7))).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kg_relation", Integer.class)).isEqualTo(1);
    }

    @Test void editsReplaceObsoleteRelationEvidenceAndDeletionKeepsNodes() {
        String a = node("u", "摄影"), b = node("u", "课程");
        String id = (String) editing.saveRelation("u", null,
            new KnowledgeGraphEditingService.RelationInput(a, b, "learns", 0.7)).get("id");
        editing.saveRelation("u", id, new KnowledgeGraphEditingService.RelationInput(a, b, "learns", 0.8));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kg_evidence WHERE relation_id=?", Integer.class, id)).isEqualTo(2);
        editing.saveRelation("u", id, new KnowledgeGraphEditingService.RelationInput(b, a, "related_to", 0.8));
        var evidence = jdbc.queryForList("SELECT user_message,session_id FROM kg_evidence WHERE relation_id=?", id);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).get("user_message")).isEqualTo("用户手动确认关系：课程 --related_to--> 摄影。");
        assertThat(evidence.get(0).get("session_id")).isEqualTo("manual:knowledge-graph");
        assertThatThrownBy(() -> editing.deleteRelation("other", id)).isInstanceOf(ResponseStatusException.class);
        assertThat(editing.deleteRelation("u", id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kg_entity", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kg_evidence WHERE relation_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }

    @Test void evidenceFailureRollsBackTheEdit() {
        String a = node("u", "摄影"), b = node("u", "课程");
        String id = (String) editing.saveRelation("u", null,
            new KnowledgeGraphEditingService.RelationInput(a, b, "learns", 0.7)).get("id");
        jdbc.execute("CREATE TRIGGER fail_evidence BEFORE INSERT ON kg_evidence BEGIN SELECT RAISE(ABORT,'test failure'); END");
        assertThatThrownBy(() -> editing.saveRelation("u", id,
            new KnowledgeGraphEditingService.RelationInput(b, a, "uses", 0.8))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT predicate FROM kg_relation WHERE id=?", String.class, id)).isEqualTo("learns");
        assertThat(jdbc.queryForObject("SELECT user_message FROM kg_evidence WHERE relation_id=?", String.class, id)).contains("--learns-->");
        assertThatThrownBy(() -> node("u", "回滚节点")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kg_entity", Integer.class)).isEqualTo(2);
    }

    @Test void manualFactsUseTheExistingRetrievalEvidencePolicyAndPersistOnReopen() {
        String a = node("u", "摄影项目"), b = node("u", "相机");
        editing.saveRelation("u", null, new KnowledgeGraphEditingService.RelationInput(a, b, "uses", 0.7));
        var retrieval = new KnowledgeGraphRetrievalService(jdbc,
            new VectorSearchService(jdbc, new Logger(), ""), MemoryRetrievalPolicy.defaults());
        assertThat(retrieval.retrieve("u", "摄影项目使用什么", null, 5)).hasSize(1)
            .allSatisfy(fact -> assertThat(fact.evidenceTexts()).anySatisfy(text -> assertThat(text).contains("用户手动确认")));
        SQLiteDataSource reopened = new SQLiteDataSource();
        reopened.setUrl("jdbc:sqlite:" + directory.resolve("graph.db").toAbsolutePath());
        assertThat(new KnowledgeGraphEditingService(new JdbcTemplate(reopened), embeddings).entities("u")).hasSize(2);
    }

    @Test void desktopHttpRoutesPersistEditsAndReturnActionableErrors() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeGraphController(
            mock(KnowledgeGraphService.class), mock(SessionService.class), editing)).build();
        var json = new ObjectMapper();
        String body = "{\"label\":\"摄影\",\"type\":\"topic\",\"summary\":\"我的项目\",\"importance\":0.7}";
        var created = mvc.perform(post("/api/desktop/knowledge-graph/entities")
                .contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok")).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/desktop/knowledge-graph/entities").contentType("application/json").content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(put("/api/desktop/knowledge-graph/entities/" + id).contentType("application/json")
                .content(body.replace("摄影", "人像摄影")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/desktop/knowledge-graph/entities"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entities[0].label").value("人像摄影"));
        String other = node("desktop-user", "相机");
        String relation = json.writeValueAsString(new KnowledgeGraphEditingService.RelationInput(id, other, "uses", 0.7));
        var saved = mvc.perform(post("/api/desktop/knowledge-graph/relations").contentType("application/json").content(relation))
            .andExpect(status().isOk()).andReturn();
        String relationId = json.readTree(saved.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(put("/api/desktop/knowledge-graph/relations/" + relationId).contentType("application/json")
                .content(relation.replace("uses", "related_to"))).andExpect(status().isOk());
        mvc.perform(delete("/api/desktop/knowledge-graph/relations/" + relationId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(put("/api/desktop/knowledge-graph/entities/missing").contentType("application/json").content(body))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value("error"));
    }
}
