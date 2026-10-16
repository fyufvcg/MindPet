package service.agent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import service.AiService;
import model.ChatResult;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentScheduleServiceTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;
    AgentScheduleService schedules;
    AgentScheduleStore repository;
    AgentScheduledTaskRunner runner;
    AiService ai;
    AgentTaskService tasks;
    AgentTaskStore store;
    @BeforeEach void setup() {
        var db = new SQLiteDataSource(); db.setUrl("jdbc:sqlite:" + temp.resolve("schedule.db"));
        jdbc = new JdbcTemplate(db); ai = mock(AiService.class); tasks = mock(AgentTaskService.class); store = mock(AgentTaskStore.class);
        repository = new AgentScheduleStore(jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(db));
        runner = new AgentScheduledTaskRunner(ai, tasks, store, mock(service.SqliteMemoryService.class));
        schedules = new AgentScheduleService(repository, runner, mock(service.UserNotificationPort.class));
    }
    @AfterEach void close() { schedules.close(); }
    Map<String,Object> task() { return Map.of("id", "one", "name", "check", "action", "read files", "interval", 60, "isActive", true); }
    @Test void migrationDoesNotResurrectDeletedSchedules() {
        repository.replace(List.of(task()), true);
        repository.replace(List.of(), false);
        repository.replace(List.of(task()), true);
        assertThat(repository.list()).isEmpty();
    }
    @Test void invalidBatchDoesNotPartiallyOverwriteExistingSchedule() {
        repository.replace(List.of(task()), false);
        assertThatThrownBy(() -> repository.replace(List.of(Map.of("id", "bad", "interval", -1)), false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.list()).hasSize(1);
    }
    @Test void invalidScopeDoesNotReplaceCurrentConfiguration() {
        repository.replace(List.of(task()), false);
        assertThatThrownBy(() -> repository.upsert("one", Map.of("selectedMcpServerIds", "all")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.list().get(0)).doesNotContainKey("selectedMcpServerIds");
        repository.upsert("one", Map.of("selectedMcpServerIds", List.of("allowed")));
        assertThat(repository.list().get(0)).containsEntry("selectedMcpServerIds", List.of("allowed"));
    }
    @Test void restartPausesUncertainScheduleAndPreservesLog() {
        repository.replace(List.of(task()), false);
        jdbc.update("UPDATE agent_schedule SET running=1,next_run=0,logs='[{\"id\":\"run\",\"status\":\"running\"}]'");
        schedules.close(); repository = new AgentScheduleStore(jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        schedules = new AgentScheduleService(repository, runner, mock(service.UserNotificationPort.class));
        schedules.tick();
        var task = repository.list().get(0);
        assertThat(task).containsEntry("isActive", false).containsEntry("running", false);
        assertThat(task.get("logs").toString()).contains("failed");
        verifyNoInteractions(ai);
    }
    @Test void wallClockClaimPreventsOverlapAndStaleEditorCannotEraseLogs() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        var notifications = mock(service.UserNotificationPort.class);
        doAnswer(inv -> { finished.countDown(); return null; }).when(notifications).sendText(anyString(), anyString(), anyString());
        schedules.close(); schedules = new AgentScheduleService(repository, runner, notifications);
        when(ai.chat(anyString(), anyString())).thenAnswer(inv -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); return ChatResult.of("done", false);
        });
        when(store.list(anyString(), anyString())).thenReturn(List.of(Map.of("id", "task", "status", "completed")));
        when(store.get(anyString(), anyString())).thenReturn(Map.of()); when(store.snapshot(anyMap())).thenReturn(Map.of());
        repository.replace(List.of(task()), false);
        jdbc.update("UPDATE agent_schedule SET next_run=0"); schedules.tick();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE agent_schedule SET next_run=0"); schedules.tick();
        repository.replace(List.of(task()), false);
        assertThat(repository.list().get(0).get("logs").toString()).contains("running");
        assertThatThrownBy(() -> repository.replace(List.of(), false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        release.countDown(); assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        verify(ai, times(1)).chat(anyString(), anyString());
        assertThat(repository.list().get(0).get("logs").toString()).contains("success");
    }
}
