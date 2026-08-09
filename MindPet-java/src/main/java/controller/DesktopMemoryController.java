package controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import service.PgVectorMemoryService;
import service.ConversationMemoryService;
import service.MemoryCuratorService;
import service.SessionService;
import service.UserProfileService;
import util.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 桌面端记忆管理 API — 查看/编辑数据库中的记忆和用户画像。
 */
@RestController
@RequestMapping("/api/desktop/memory")
public class DesktopMemoryController {

    private final PgVectorMemoryService pgMemory;
    private final UserProfileService profileService;
    private final ConversationMemoryService convMemory;
    private final MemoryCuratorService memoryCurator;
    private final SessionService sessionService;
    private final JdbcTemplate jdbc;
    private final Logger logger;

    private static final String USER_ID = "desktop-user";

    @Autowired
    public DesktopMemoryController(
            PgVectorMemoryService pgMemory,
            UserProfileService profileService,
            ConversationMemoryService convMemory,
            MemoryCuratorService memoryCurator,
            SessionService sessionService,
            JdbcTemplate jdbc,
            Logger logger) {
        this.pgMemory = pgMemory;
        this.profileService = profileService;
        this.convMemory = convMemory;
        this.memoryCurator = memoryCurator;
        this.sessionService = sessionService;
        this.jdbc = jdbc;
        this.logger = logger;
    }

    @GetMapping("/curator/status")
    public Map<String, Object> getCuratorStatus() {
        try {
            return Map.of("status", "ok", "curator", memoryCurator.getStatus(USER_ID));
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @PostMapping("/curator/retry")
    public Map<String, Object> retryCurator() {
        try {
            return Map.of("status", "ok", "scheduled", memoryCurator.retry(USER_ID));
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== 用户画像 ====================

    @GetMapping("/profile")
    public Map<String, Object> getProfile() {
        try {
            String profile = profileService.getProfileContext(USER_ID);
            return Map.of("status", "ok", "profile", profile != null ? profile : "");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody Map<String, String> body) {
        try {
            String text = body.getOrDefault("profile", "");
            // UserProfileService stores as KV: category=profile, key=summary
            profileService.save(USER_ID, "profile", "summary", text);
            logger.log("INFO", "[Desktop] 用户画像已更新 (" + text.length() + "字符)");
            return Map.of("status", "ok");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== 长期记忆 ====================

    @GetMapping("/list")
    public Map<String, Object> listMemories(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "") String query) {
        try {
            List<Map<String, Object>> results;
            if (!query.isBlank()) {
                var memories = pgMemory.search(USER_ID, query, limit);
                results = toResultList(memories);
            } else {
                var memories = pgMemory.getRecent(USER_ID, limit);
                results = toResultList(memories);
            }
            return Map.of("status", "ok", "memories", results, "count", results.size());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage(), "memories", List.of());
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteMemory(@PathVariable String id) {
        try {
            int deleted = pgMemory.delete(id);
            logger.log("INFO", "[Desktop] 记忆已删除: " + id + " (" + deleted + "行)");
            return Map.of("status", "ok", "deleted", deleted);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @PostMapping("/purify")
    public Map<String, Object> purifyMemories() {
        try {
            int pruned = pgMemory.prune(USER_ID);
            logger.log("INFO", "[Desktop] 记忆净化完成，清理 " + pruned + " 条");
            return Map.of("status", "ok", "pruned", pruned);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> memoryStats() {
        try {
            int total = pgMemory.count(USER_ID);
            return Map.of("status", "ok", "longTermCount", total);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== 对话历史 ====================

    @GetMapping("/conversations")
    public Map<String, Object> listConversations(@RequestParam(defaultValue = "30") int limit) {
        try {
            int safeLimit = Math.max(1, Math.min(limit, 1000));
            // SessionService is the source of truth for completed chat messages.
            // long_term_memory only contains the subset selected as durable memories.
            List<Map<String, Object>> storedMessages = loadArchiveMessages();
            int userMessages = 0;
            int assistantMessages = 0;
            Set<String> companionDates = new HashSet<>();
            int conversationRounds = Math.min(userMessages, assistantMessages);
            Map<String, Integer> activityCounts = new TreeMap<>();
            for (Map<String, Object> stored : storedMessages) {
                String role = archiveRole(stored.get("sender"));
                if (role.isBlank()) continue;
                if ("user".equals(role)) userMessages++;
                else assistantMessages++;

                String date = archiveDate(stored.get("time"));
                if (!date.isBlank()) {
                    companionDates.add(date);
                    activityCounts.merge(date, 1, Integer::sum);
                }
            }
            conversationRounds = Math.min(userMessages, assistantMessages);

            List<Map<String, Object>> messages = new ArrayList<>();
            for (Map<String, Object> stored : storedMessages) {
                String role = archiveRole(stored.get("sender"));
                if (role.isBlank()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", role);
                item.put("content", stored.getOrDefault("text", ""));
                item.put("emotion", stored.getOrDefault("emotion", ""));
                item.put("time", archiveTime(stored.get("time")));
                messages.add(item);
                if (messages.size() == safeLimit) break;
            }
            List<Map<String, Object>> results = new ArrayList<>();
            results.addAll(messages);
            return Map.of(
                "status", "ok",
                "messages", results,
                "count", results.size(),
                "conversationRounds", conversationRounds,
                "companionDays", companionDates.size(),
                "activityByDate", activityCounts
            );
        } catch (Exception e) {
            return Map.of(
                "status", "error",
                "message", String.valueOf(e.getMessage()),
                "messages", List.of(),
                "conversationRounds", 0,
                "companionDays", 0,
                "activityByDate", Map.of()
            );
        }
    }

    private List<Map<String, Object>> loadArchiveMessages() {
        try {
            List<Map<String, Object>> messages = sessionService.loadAllMessages(USER_ID, 0);
            if (!messages.isEmpty()) return messages;
        } catch (Exception e) {
            logger.log("WARN", "[Desktop] 会话消息读取失败，回退长期记忆: " + e.getMessage());
        }

        return jdbc.query(
            "SELECT role, content, emotion, created_at "
                + "FROM long_term_memory WHERE user_id = ? "
                + "AND role IN ('user', 'assistant', 'agent') "
                + "ORDER BY created_at DESC",
            (rs, rowNum) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sender", "user".equals(rs.getString("role")) ? "user" : "agent");
                item.put("text", rs.getString("content"));
                item.put("emotion", rs.getString("emotion"));
                var createdAt = rs.getTimestamp("created_at");
                item.put("time", createdAt != null ? createdAt.toInstant().toString() : "");
                return item;
            },
            USER_ID
        );
    }

    private String archiveRole(Object sender) {
        String value = String.valueOf(sender == null ? "" : sender).trim();
        if ("user".equalsIgnoreCase(value)) return "user";
        if ("agent".equalsIgnoreCase(value) || "assistant".equalsIgnoreCase(value)) return "assistant";
        return "";
    }

    private String archiveDate(Object rawTime) {
        String time = String.valueOf(rawTime == null ? "" : rawTime).trim();
        if (time.isBlank()) return "";
        try {
            return LocalDateTime.parse(time).toLocalDate().toString();
        } catch (RuntimeException ignored) {
            try {
                return Instant.parse(time).atZone(ZoneId.systemDefault()).toLocalDate().toString();
            } catch (RuntimeException ignoredInstant) {
                return "";
            }
        }
    }

    private String archiveTime(Object rawTime) {
        String time = String.valueOf(rawTime == null ? "" : rawTime).trim();
        if (time.isBlank()) return "";
        try {
            return LocalDateTime.parse(time).atZone(ZoneId.systemDefault()).toInstant().toString();
        } catch (RuntimeException ignored) {
            return time;
        }
    }

    // ==================== 批量编辑（文本编辑器模式） ====================

    /**
     * 导出所有记忆为可编辑文本格式。
     * 格式：每行一条记忆，[重要性] [角色] [时间] 内容
     */
    @GetMapping("/export")
    public Map<String, Object> exportMemories() {
        try {
            var memories = pgMemory.getRecent(USER_ID, 200);
            StringBuilder sb = new StringBuilder();
            sb.append("# MindPet 记忆数据 - 可直接编辑后保存\n");
            sb.append("# 格式: [重要性0-1] [user/mindpet] [时间] 内容\n");
            sb.append("# 删除某行即可删除该记忆，修改内容后保存即可更新\n\n");

            for (var m : memories) {
                sb.append(String.format("[%.1f] [%s] [%s] %s\n",
                    m.importance(),
                    m.role(),
                    m.createdAt() != null ? m.createdAt().toString().substring(0, 16) : "?",
                    m.content()));
            }
            return Map.of("status", "ok", "text", sb.toString(), "count", memories.size());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * 导入编辑后的文本并更新数据库。
     * 当前实现：全量替换——清空旧记忆，写入新内容。
     */
    @PutMapping("/import")
    public Map<String, Object> importMemories(@RequestBody Map<String, String> body) {
        try {
            String text = body.getOrDefault("text", "");
            int imported = 0;
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // 去掉 [importance] [role] [time] 前缀
                String content = line.replaceFirst("^\\[\\d\\.?\\d*\\]\\s*\\[\\w+\\]\\s*\\[[^]]+\\]\\s*", "").trim();
                if (!content.isBlank()) {
                    // TODO: 全量替换逻辑，当前简单追加
                    pgMemory.append(USER_ID, content, "manual", 0.5, "neutral");
                    imported++;
                }
            }
            logger.log("INFO", "[Desktop] 记忆导入完成，新增 " + imported + " 条");
            return Map.of("status", "ok", "imported", imported);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== 四表统一管理 ====================

    /** 表名→中文名映射 */
    private static final Map<String, String> TABLES = Map.of(
        "long_term_memory", "长期记忆",
        "user_profile", "人物画像",
        "user_insight", "用户启发",
        "llm_growth", "LLM成长"
    );

    /** 列出任意表的数据（管理用，不限 user_id；支持分页+搜索） */
    @GetMapping("/table/{table}")
    public Map<String, Object> listTable(
            @PathVariable String table,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "") String search) {
        if (!TABLES.containsKey(table)) {
            return Map.of("status", "error", "message", "未知表: " + table);
        }
        try {
            String baseSql = switch (table) {
                case "long_term_memory" ->
                    "SELECT id, user_id, content, role, importance, emotion, created_at FROM long_term_memory";
                case "user_profile" ->
                    "SELECT user_id, category, prop_key, prop_value, updated_at FROM user_profile";
                case "user_insight" ->
                    "SELECT id, user_id, insight AS content, context, created_at FROM user_insight";
                case "llm_growth" ->
                    "SELECT id, user_id, category, insight AS content, created_at FROM llm_growth";
                default -> throw new IllegalArgumentException("unknown");
            };

            String countSql = "SELECT COUNT(*) FROM " + table;
            List<Object> whereParams = new ArrayList<>();
            StringBuilder whereClause = new StringBuilder();

            // 搜索过滤
            if (!search.isBlank()) {
                String like = "%" + search + "%";
                whereClause.append(" WHERE (");
                switch (table) {
                    case "long_term_memory" -> {
                        whereClause.append("content ILIKE ? OR role ILIKE ? OR emotion ILIKE ?");
                        whereParams.add(like); whereParams.add(like); whereParams.add(like);
                    }
                    case "user_profile" -> {
                        whereClause.append("category ILIKE ? OR prop_key ILIKE ? OR prop_value ILIKE ?");
                        whereParams.add(like); whereParams.add(like); whereParams.add(like);
                    }
                    case "user_insight" -> {
                        whereClause.append("insight ILIKE ? OR context ILIKE ?");
                        whereParams.add(like); whereParams.add(like);
                    }
                    case "llm_growth" -> {
                        whereClause.append("insight ILIKE ? OR category ILIKE ?");
                        whereParams.add(like); whereParams.add(like);
                    }
                }
                whereClause.append(")");
            }

            // 总数 — PostgreSQL COUNT(*) 返回 bigint，必须用 Long.class
            Long totalLong;
            if (!whereParams.isEmpty()) {
                totalLong = jdbc.queryForObject(countSql + whereClause, Long.class, whereParams.toArray());
            } else {
                totalLong = jdbc.queryForObject(countSql, Long.class);
            }
            int total = totalLong != null ? totalLong.intValue() : 0;

            // 分页数据
            int offset = Math.max(0, (page - 1) * limit);
            String orderCol = "user_profile".equals(table) ? "updated_at" : "created_at";
            String dataSql = baseSql + whereClause + " ORDER BY " + orderCol + " DESC LIMIT ? OFFSET ?";
            List<Object> dataParams = new ArrayList<>(whereParams);
            dataParams.add(Integer.valueOf(limit));
            dataParams.add(Integer.valueOf(offset));
            logger.log("DEBUG", "[Desktop] SQL: " + dataSql + " | params: " + dataParams);
            List<Map<String, Object>> rows = jdbc.queryForList(dataSql, dataParams.toArray(new Object[0]));

            int totalPages = (int) Math.ceil((double) total / limit);
            return Map.of(
                "status", "ok",
                "table", table,
                "rows", rows,
                "count", rows.size(),
                "total", total,
                "page", page,
                "limit", limit,
                "totalPages", totalPages
            );
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /** 删除任意表的记录（管理用，不限 user_id） */
    @DeleteMapping("/table/{table}/{id}")
    public Map<String, Object> deleteFromTable(
            @PathVariable String table, @PathVariable String id) {
        if (!TABLES.containsKey(table)) {
            return Map.of("status", "error", "message", "未知表: " + table);
        }
        try {
            int deleted;
            if ("user_profile".equals(table)) {
                String[] parts = parseCompositeId3(id);
                if (!parts[0].isBlank()) {
                    deleted = jdbc.update(
                        "DELETE FROM user_profile WHERE user_id=? AND category=? AND prop_key=?",
                        parts[0], parts[1], parts[2]);
                } else {
                    deleted = jdbc.update(
                        "DELETE FROM user_profile WHERE category=? AND prop_key=?",
                        parts[1], parts[2]);
                }
            } else {
                String sql = switch (table) {
                    case "long_term_memory" -> "DELETE FROM long_term_memory WHERE id=?";
                    case "user_insight" -> "DELETE FROM user_insight WHERE id=?";
                    case "llm_growth" -> "DELETE FROM llm_growth WHERE id=?";
                    default -> throw new IllegalArgumentException("unknown");
                };
                deleted = jdbc.update(sql, Integer.parseInt(id));
            }
            logger.log("INFO", "[Desktop] 删除 " + table + " id=" + id + " (" + deleted + "行)");
            return Map.of("status", "ok", "deleted", deleted);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /** 更新任意表的记录（管理用，不限 user_id） */
    @PutMapping("/table/{table}/{id}")
    public Map<String, Object> updateTable(
            @PathVariable String table, @PathVariable String id,
            @RequestBody Map<String, String> body) {
        if (!TABLES.containsKey(table)) {
            return Map.of("status", "error", "message", "未知表: " + table);
        }
        try {
            String content = body.getOrDefault("content", "");
            String category = body.getOrDefault("category", "");
            String propKey = body.getOrDefault("propKey", "");
            String importance = body.getOrDefault("importance", "");
            String emotion = body.getOrDefault("emotion", "");
            String role = body.getOrDefault("role", "");

            int updated;
            if ("user_profile".equals(table)) {
                // id: user_id||category||prop_key 或旧格式 category||prop_key
                String[] parts = parseCompositeId3(id);
                String uid = parts[0];      // 可能为空（旧格式）
                String oldCat = parts[1];
                String oldKey = parts[2];
                if (content.isBlank()) {
                    return Map.of("status", "error", "message", "prop_value 不能为空");
                }
                String cat = !category.isBlank() ? category : oldCat;
                String key = !propKey.isBlank() ? propKey : oldKey;
                boolean hasUid = !uid.isBlank();

                if (!cat.equals(oldCat) || !key.equals(oldKey)) {
                    // 主键变了：DELETE 旧行 + INSERT 新行
                    if (hasUid) {
                        jdbc.update("DELETE FROM user_profile WHERE user_id=? AND category=? AND prop_key=?", uid, oldCat, oldKey);
                    } else {
                        jdbc.update("DELETE FROM user_profile WHERE category=? AND prop_key=?", oldCat, oldKey);
                    }
                    jdbc.update("INSERT INTO user_profile (user_id, category, prop_key, prop_value) VALUES (?,?,?,?)",
                        hasUid ? uid : USER_ID, cat, key, content);
                    updated = 1;
                } else {
                    if (hasUid) {
                        updated = jdbc.update("UPDATE user_profile SET prop_value=? WHERE user_id=? AND category=? AND prop_key=?",
                            content, uid, oldCat, oldKey);
                    } else {
                        updated = jdbc.update("UPDATE user_profile SET prop_value=? WHERE category=? AND prop_key=?",
                            content, oldCat, oldKey);
                    }
                }
            } else if ("long_term_memory".equals(table)) {
                logger.log("INFO", "[Desktop] 更新长期记忆 id=" + id + " body=" + body);
                StringBuilder setClauses = new StringBuilder();
                List<Object> params = new ArrayList<>();
                if (!content.isBlank()) { setClauses.append("content=?, "); params.add(content); }
                if (!importance.isBlank()) { setClauses.append("importance=?, "); params.add(Double.parseDouble(importance)); }
                if (!emotion.isBlank()) { setClauses.append("emotion=?, "); params.add(emotion); }
                if (!role.isBlank()) { setClauses.append("role=?, "); params.add(role); }
                if (setClauses.isEmpty()) {
                    return Map.of("status", "error", "message", "没有需要更新的字段");
                }
                setClauses.setLength(setClauses.length() - 2);
                params.add(Integer.parseInt(id));
                String sql = "UPDATE long_term_memory SET " + setClauses + " WHERE id=?";
                logger.log("INFO", "[Desktop] SQL: " + sql + " params=" + params);
                updated = jdbc.update(sql, params.toArray());
                logger.log("INFO", "[Desktop] 更新结果: " + updated + " 行");
            } else if ("user_insight".equals(table)) {
                if (content.isBlank()) {
                    return Map.of("status", "error", "message", "content 不能为空");
                }
                String context = body.getOrDefault("context", "");
                updated = jdbc.update(
                    "UPDATE user_insight SET insight=?, context=? WHERE id=?",
                    content, context, Integer.parseInt(id));
            } else {
                if (content.isBlank()) {
                    return Map.of("status", "error", "message", "content 不能为空");
                }
                String col = "insight";
                String sql = "UPDATE " + table + " SET " + col + "=?";
                List<Object> params = new ArrayList<>();
                params.add(content);
                if (!category.isBlank()) {
                    sql += ", category=?";
                    params.add(category);
                }
                sql += " WHERE id=?";
                params.add(Integer.parseInt(id));
                updated = jdbc.update(sql, params.toArray());
            }
            logger.log("INFO", "[Desktop] 更新 " + table + " id=" + id + " (" + updated + "行)");
            return Map.of("status", "ok", "updated", updated);
        } catch (Exception e) {
            logger.log("ERROR", "[Desktop] 更新失败 " + table + " id=" + id + ": " + e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /** 获取所有表的统计（管理用，不限 user_id） */
    @GetMapping("/tables")
    public Map<String, Object> allTables() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : TABLES.keySet()) {
            try {
                Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table, Long.class);
                counts.put(table, c != null ? c : 0);
            } catch (Exception e) {
                counts.put(table, 0);
            }
        }
        result.put("tables", counts);
        return result;
    }

    /** 创建新记录 */
    @PostMapping("/table/{table}")
    public Map<String, Object> createInTable(
            @PathVariable String table,
            @RequestBody Map<String, String> body) {
        if (!TABLES.containsKey(table)) {
            return Map.of("status", "error", "message", "未知表: " + table);
        }
        try {
            String content = body.getOrDefault("content", "");
            String category = body.getOrDefault("category", "");
            String propKey = body.getOrDefault("propKey", "");
            String propValue = body.getOrDefault("propValue", content);
            String importance = body.getOrDefault("importance", "0.5");
            String emotion = body.getOrDefault("emotion", "neutral");
            String role = body.getOrDefault("role", "manual");

            switch (table) {
                case "long_term_memory" -> {
                    if (content.isBlank()) {
                        return Map.of("status", "error", "message", "content 不能为空");
                    }
                    pgMemory.append(USER_ID, content, role,
                        Double.parseDouble(importance), emotion);
                    return Map.of("status", "ok", "message", "长期记忆已添加");
                }
                case "user_profile" -> {
                    if (category.isBlank() || propKey.isBlank()) {
                        return Map.of("status", "error", "message", "category 和 propKey 不能为空");
                    }
                    profileService.save(USER_ID, category, propKey,
                        !propValue.isBlank() ? propValue : "");
                    return Map.of("status", "ok", "message", "用户画像已添加");
                }
                case "user_insight" -> {
                    if (content.isBlank()) {
                        return Map.of("status", "error", "message", "content 不能为空");
                    }
                    // 生成零向量占位（1024维），后续可由 MemoryCurator 重新生成
                    String zeroVec = "[" + String.join(",", Collections.nCopies(1024, "0")) + "]";
                    jdbc.update(
                        "INSERT INTO user_insight (user_id, insight, context, embedding) VALUES (?,?,?,?::vector)",
                        USER_ID, content, body.getOrDefault("context", ""), zeroVec);
                    return Map.of("status", "ok", "message", TABLES.get(table) + "已添加");
                }
                case "llm_growth" -> {
                    if (content.isBlank()) {
                        return Map.of("status", "error", "message", "content 不能为空");
                    }
                    String cat = !category.isBlank() ? category : "manual";
                    String zeroVec = "[" + String.join(",", Collections.nCopies(1024, "0")) + "]";
                    jdbc.update(
                        "INSERT INTO llm_growth (user_id, category, insight, embedding) VALUES (?,?,?,?::vector)",
                        USER_ID, cat, content, zeroVec);
                    return Map.of("status", "ok", "message", TABLES.get(table) + "已添加");
                }
                default -> {
                    return Map.of("status", "error", "message", "不支持的表: " + table);
                }
            }
        } catch (Exception e) {
            logger.log("ERROR", "[Desktop] 创建记录失败: " + e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== helper ====================

    /** 解析复合ID（2段）：category||prop_key → [category, prop_key] */
    private String[] parseCompositeId(String id) {
        int idx = id.indexOf("||");
        if (idx > 0) {
            return new String[]{ id.substring(0, idx), id.substring(idx + 2) };
        }
        return new String[]{ id, "" };
    }

    /** 解析复合ID（3段）：user_id||category||prop_key → [user_id, category, prop_key]
     *  自动兼容旧2段格式 category||prop_key → ["", category, prop_key] */
    private String[] parseCompositeId3(String id) {
        // 统计 || 出现次数来区分新旧格式
        int count = 0;
        int idx = 0;
        while ((idx = id.indexOf("||", idx)) != -1) {
            count++;
            idx += 2;
        }
        if (count >= 2) {
            // 新格式：user_id||category||prop_key
            int i1 = id.indexOf("||");
            int i2 = id.indexOf("||", i1 + 2);
            return new String[]{
                id.substring(0, i1),
                id.substring(i1 + 2, i2),
                id.substring(i2 + 2)
            };
        } else if (count == 1) {
            // 旧格式：category||prop_key
            String[] parts = parseCompositeId(id);
            return new String[]{ "", parts[0], parts[1] };
        } else {
            return new String[]{ "", id, "" };
        }
    }

    private List<Map<String, Object>> toResultList(List<PgVectorMemoryService.MemoryResult> memories) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (var m : memories) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.id() != null ? m.id() : "");
            item.put("content", m.content());
            item.put("role", m.role());
            item.put("importance", m.importance());
            item.put("emotion", m.emotion());
            item.put("layer", m.layer());
            item.put("createdAt", m.createdAt() != null ? m.createdAt().toString() : "");
            results.add(item);
        }
        return results;
    }
}
