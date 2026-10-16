package service.agent;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import service.UserNotificationPort;
import java.util.Map;
import java.util.concurrent.*;

/** Scheduling only: claims from the store, bounded workers, execution through the shared task runner. */
@Service
public class AgentScheduleService {
    private final AgentScheduleStore store;
    private final AgentScheduledTaskRunner runner;
    private final UserNotificationPort notifications;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), r -> { var t = new Thread(r, "agent-schedule"); t.setDaemon(true); return t; });
    public AgentScheduleService(AgentScheduleStore store, AgentScheduledTaskRunner runner, UserNotificationPort notifications) {
        this.store = store; this.runner = runner; this.notifications = notifications;
    }
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        for (var claim : store.claimDue(System.currentTimeMillis())) {
            try { workers.execute(() -> finish(claim, runner.execute(claim))); }
            catch (RejectedExecutionException e) { finish(claim, new AgentScheduledTaskRunner.Result("failed", "后台队列已满，本次未执行。", "", Map.of())); }
        }
    }
    private void finish(AgentScheduleStore.Claim claim, AgentScheduledTaskRunner.Result result) {
        if (store.finish(claim.id(), claim.request(), result)) {
            notifications.sendText("desktop-user", "任务「" + claim.config().get("name") + "」" +
                ("success".equals(result.status()) ? "已完成" : "需要处理"), result.message());
        }
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
