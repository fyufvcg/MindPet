package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import util.Logger;

/**
 * Periodic source retirement with explicit validity and conservative evidence protection.
 *
 * This is deliberately isolated from retrieval, ingestion and curator code. The
 * existing prune operation already updates both the raw row and any mapped
 * retrieval unit, so the maintenance pass only supplies the missing trigger.
 */
@Service
public class MemoryForgettingMaintenanceService {
    private final JdbcTemplate jdbc;
    private final SqliteMemoryService memoryStore;
    private final Logger logger;

    public MemoryForgettingMaintenanceService(JdbcTemplate jdbc, SqliteMemoryService memoryStore, Logger logger) {
        this.jdbc = jdbc;
        this.memoryStore = memoryStore;
        this.logger = logger;
    }

    @Scheduled(
        fixedDelayString = "${app.memory.forgetting.maintenance-delay-ms:900000}",
        initialDelayString = "${app.memory.forgetting.maintenance-initial-delay-ms:30000}")
    public void scheduledPrune() {
        pruneAllUsers();
    }

    /** Runs one bounded maintenance pass and returns the number of retired rows. */
    public int pruneAllUsers() {
        try {
            int retired = 0;
            for (String userId : jdbc.query(
                    "SELECT user_id FROM long_term_memory WHERE searchable=1 UNION "
                        + "SELECT user_id FROM memory_retrieval_unit WHERE unit_type='residual_memory' "
                        + "AND status='active' AND searchable=1 AND scope IN ('episodic','planned') ORDER BY user_id",
                    (rs, row) -> rs.getString(1))) {
                retired += memoryStore.prune(userId);
            }
            if (retired > 0) logger.log("INFO", "遗忘维护完成，软退役 " + retired + " 条记忆");
            return retired;
        } catch (Exception e) {
            logger.log("WARN", "遗忘维护失败: " + e.getMessage());
            return 0;
        }
    }
}
