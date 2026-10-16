package service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import service.v3.SecretSanitizer;
import java.util.*;

/** Runtime archives only; never writes memory, curator or session-message tables. */
@Service
public class AgentContextStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public AgentContextStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_context_message (sequence INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,session_id TEXT NOT NULL,message_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,UNIQUE(user_id,session_id,message_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_agent_context_history ON agent_context_message(user_id,session_id,sequence)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_context_checkpoint (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,session_id TEXT NOT NULL,covered_sequence INTEGER NOT NULL,summary TEXT NOT NULL,created_at INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_context_trace (task_id TEXT NOT NULL,round INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(task_id,round))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_context_image (user_id TEXT NOT NULL,session_id TEXT NOT NULL,message_id TEXT NOT NULL,images TEXT NOT NULL,PRIMARY KEY(user_id,session_id,message_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_context_import (user_id TEXT NOT NULL,session_id TEXT NOT NULL,message_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(user_id,session_id,message_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_context_contract (user_id TEXT NOT NULL,session_id TEXT NOT NULL,contract_id TEXT NOT NULL,source_sequence INTEGER NOT NULL,quote TEXT NOT NULL,PRIMARY KEY(user_id,session_id,contract_id))");
    }
    public void append(String user, String session, String messageId, String role, String content) {
        if (session.isBlank()) return;
        String clean = SecretSanitizer.sanitize(content);
        var prior = jdbc.queryForList("SELECT sequence,role,content FROM agent_context_message WHERE user_id=? AND session_id=? AND message_id=?", user, session, messageId);
        if (!prior.isEmpty() && (!clean.equals(prior.get(0).get("content")) || !role.equals(prior.get(0).get("role")))) {
            jdbc.update("DELETE FROM agent_context_contract WHERE user_id=? AND session_id=? AND source_sequence=? AND (?<>'user' OR instr(?,quote)=0)", user, session, prior.get(0).get("sequence"), role, clean);
            var previous = latest(user, session);
            if (((Number)previous.getOrDefault("covered_sequence", 0L)).longValue() >= ((Number)prior.get(0).get("sequence")).longValue())
                checkpoint(user, session, 0, ""); // An edit invalidates coverage; preserve previous versions for recovery.
        }
        jdbc.update("INSERT INTO agent_context_message(user_id,session_id,message_id,role,content) VALUES(?,?,?,?,?) ON CONFLICT(user_id,session_id,message_id) DO UPDATE SET role=excluded.role,content=excluded.content",
            user, session, messageId, role, clean);
    }
    /** Import existing UI records without changing their retention or summary flags. */
    public void importSession(String user, String session, String current, String reply) {
        if (session.isBlank()) return;
        for (String payload : jdbc.query("SELECT s.payload_json FROM session_messages s LEFT JOIN agent_context_import i ON i.user_id=s.user_id AND i.session_id=s.session_id AND i.message_id=s.message_id WHERE s.user_id=? AND s.session_id=? AND (i.payload IS NULL OR i.payload<>s.payload_json) ORDER BY s.sequence", (rs,n) -> rs.getString(1), user, session)) {
            try {
                var m = json.readTree(payload);
                String id = m.path("id").asText();
                if (id.isBlank() || id.equals(current) || id.equals(reply) || m.path("isThinking").asBoolean()) continue;
                String sender = m.path("sender").asText();
                if (!Set.of("user", "agent").contains(sender)) continue;
                String role = m.path("isSuperseded").asBoolean() ? "hidden" : sender.equals("user") ? "user" : "assistant";
                if (role.equals("assistant") && !jdbc.queryForList("SELECT 1 FROM agent_context_message WHERE user_id=? AND session_id=? AND message_id=?", user, session, id).isEmpty()) {
                    markImported(user, session, id, payload); continue;
                }
                StringBuilder content = new StringBuilder(m.path("text").asText());
                if (m.path("isError").asBoolean()) content.insert(0, "[本轮执行失败或中断，不能视为完成]\n");
                var files = m.path("fileInfos");
                if (files.isArray()) for (var file : files) appendFile(content, file);
                else appendFile(content, m.path("fileInfo"));
                append(user, session, id, role, content.toString());
                markImported(user, session, id, payload);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("会话记录无法解析，未执行有损恢复", e);
            }
        }
    }
    private void markImported(String user, String session, String id, String payload) {
        jdbc.update("INSERT OR REPLACE INTO agent_context_import(user_id,session_id,message_id,payload) VALUES(?,?,?,?)", user, session, id, payload);
    }
    public List<Map<String,Object>> historyPage(String user, String session, long after, long before, String exclude, int limit) {
        return jdbc.queryForList("SELECT sequence,message_id,role,content,EXISTS(SELECT 1 FROM agent_context_image i WHERE i.user_id=m.user_id AND i.session_id=m.session_id AND i.message_id=m.message_id) AS has_images FROM agent_context_message m WHERE user_id=? AND session_id=? AND sequence>? AND sequence<? AND message_id<>? AND role<>'hidden' ORDER BY sequence DESC LIMIT ?", user, session, after, before, exclude, Math.max(1, Math.min(64, limit)));
    }
    private void appendFile(StringBuilder text, com.fasterxml.jackson.databind.JsonNode file) {
        if (!file.isObject()) return;
        text.append("\n[附件 ").append(file.path("name").asText()).append(" path=").append(file.path("path").asText()).append("]\n")
            .append(file.path("content").asText());
    }
    public List<Map<String,Object>> history(String user, String session, long after, String exclude) {
        return jdbc.queryForList("SELECT sequence,message_id,role,content,EXISTS(SELECT 1 FROM agent_context_image i WHERE i.user_id=m.user_id AND i.session_id=m.session_id AND i.message_id=m.message_id) AS has_images FROM agent_context_message m WHERE user_id=? AND session_id=? AND sequence>? AND message_id<>? AND role<>'hidden' ORDER BY sequence", user, session, after, exclude);
    }
    public Map<String,Object> latest(String user, String session) {
        var rows = jdbc.queryForList("SELECT * FROM agent_context_checkpoint WHERE user_id=? AND session_id=? ORDER BY id DESC LIMIT 1", user, session);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
    public void checkpoint(String user, String session, long covered, String summary) {
        jdbc.update("INSERT INTO agent_context_checkpoint(user_id,session_id,covered_sequence,summary,created_at) VALUES(?,?,?,?,?)", user, session, covered, SecretSanitizer.sanitize(summary), System.currentTimeMillis());
    }
    public void checkpointIfCurrent(String user, String session, long expectedId, long covered, String summary) {
        jdbc.update("INSERT INTO agent_context_checkpoint(user_id,session_id,covered_sequence,summary,created_at) SELECT ?,?,?,?,? WHERE COALESCE((SELECT MAX(id) FROM agent_context_checkpoint WHERE user_id=? AND session_id=?),0)=?", user, session, covered, SecretSanitizer.sanitize(summary), System.currentTimeMillis(), user, session, expectedId);
    }
    public String read(String user, String session, String query, long after, int limit, int offset) {
        return read(user, session, query, after, limit, offset, false);
    }
    public String read(String user, String session, String query, long after, int limit, int offset, boolean includeImages) {
        var rows = jdbc.queryForList("SELECT sequence,message_id,role,content FROM agent_context_message WHERE user_id=? AND session_id=? AND sequence>? AND role<>'hidden' AND (?='' OR instr(lower(content),lower(?))>0) ORDER BY sequence LIMIT ?", user, session, Math.max(0, after), query, query, Math.max(1, Math.min(20, limit)));
        var result = new ArrayList<Map<String,Object>>();
        for (var row : rows) {
            var copy = new LinkedHashMap<>(row);
            String value = copy.remove("content").toString();
            int start = Math.min(value.length(), Math.max(0, offset));
            String fragment = ContextTokens.head(value.substring(start), Math.max(128, 3000 / Math.max(1, rows.size())));
            copy.put("content", fragment); copy.put("offset", start); copy.put("nextOffset", start + fragment.length());
            copy.put("totalLength", value.length()); copy.put("truncated", start + fragment.length() < value.length());
            result.add(copy);
        }
        List<Object> images = new ArrayList<>();
        if (includeImages && !rows.isEmpty()) {
            var saved = jdbc.queryForList("SELECT images FROM agent_context_image WHERE user_id=? AND session_id=? AND message_id=?", user, session, rows.get(0).get("message_id"));
            try { if (!saved.isEmpty()) images.addAll(json.readValue(saved.get(0).get("images").toString(), List.class)); }
            catch (Exception e) { throw new IllegalStateException("历史图片无法读取", e); }
        }
        try { return json.writeValueAsString(Map.of("mindpetToolResult", true, "historicalAttachments", true, "messages", result, "images", images, "nextSequence", rows.isEmpty() ? after : rows.get(rows.size() - 1).get("sequence"))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public void images(String user, String session, String messageId, List<String> images) {
        if (images.isEmpty()) return;
        List<Map<String,String>> media = new ArrayList<>();
        for (String data : images) {
            if (data.length() > 11_000_000) throw new IllegalArgumentException("单张图片过大，请缩小后重试");
            byte[] bytes = Base64.getDecoder().decode(data);
            String mime = bytes.length > 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xd8 ? "image/jpeg"
                : bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' ? "image/gif"
                : bytes.length > 12 && bytes[8] == 'W' && bytes[9] == 'E' ? "image/webp" : "image/png";
            media.add(Map.of("mimeType", mime, "data", data));
        }
        try { jdbc.update("INSERT OR REPLACE INTO agent_context_image(user_id,session_id,message_id,images) VALUES(?,?,?,?)", user, session, messageId, json.writeValueAsString(media)); }
        catch (Exception e) { throw new IllegalStateException("无法保存图片资料", e); }
    }
    public List<Map<String,Object>> contracts(String user, String session) {
        return jdbc.queryForList("SELECT contract_id,source_sequence,quote FROM agent_context_contract WHERE user_id=? AND session_id=? ORDER BY source_sequence,contract_id", user, session);
    }
    /** Quotes must refer to actual user text; task data cannot silently become a user instruction. */
    @org.springframework.transaction.annotation.Transactional
    public void contract(String user, String session, String id, long sequence, String quote) {
        if (id.isBlank() || id.length() > 80) throw new IllegalArgumentException("约束 id 长度须为 1–80 字符");
        if (quote.isBlank()) {
            jdbc.update("DELETE FROM agent_context_contract WHERE user_id=? AND session_id=? AND contract_id=?", user, session, id); return;
        }
        var source = jdbc.queryForList("SELECT content FROM agent_context_message WHERE user_id=? AND session_id=? AND sequence=? AND role='user'", user, session, sequence);
        if (source.isEmpty() || !source.get(0).get("content").toString().contains(quote)) throw new IllegalArgumentException("约束必须引用指定用户消息的原文");
        var candidate = new ArrayList<>(contracts(user, session).stream().filter(r -> !id.equals(r.get("contract_id"))).toList());
        candidate.add(Map.of("contract_id", id, "source_sequence", sequence, "quote", quote));
        if (ContextTokens.count(json.valueToTree(candidate).toString()) > 4000) throw new IllegalArgumentException("任务约束超过 4000 token，请先释放已失效要求");
        jdbc.update("INSERT OR REPLACE INTO agent_context_contract(user_id,session_id,contract_id,source_sequence,quote) VALUES(?,?,?,?,?)", user, session, id, sequence, SecretSanitizer.sanitize(quote));
    }
    @org.springframework.transaction.annotation.Transactional
    public void clearConversation(String user, String session) {
        clear(user, session);
        jdbc.update("DELETE FROM session_messages WHERE user_id=? AND session_id=?", user, session);
        jdbc.update("UPDATE sessions SET context_summary='',updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?", user, session);
    }
    @org.springframework.transaction.annotation.Transactional
    public void clearAllConversations(String user) {
        var sessions = jdbc.queryForList("SELECT id AS session_id FROM sessions WHERE user_id=? UNION SELECT session_id FROM agent_context_message WHERE user_id=? UNION SELECT session_id FROM agent_task WHERE user_id=?", user, user, user);
        for (var row : sessions) clearConversation(user, row.get("session_id").toString());
    }
    @org.springframework.transaction.annotation.Transactional
    public void deleteConversation(String user, String session) {
        clearConversation(user, session);
        jdbc.update("DELETE FROM sessions WHERE user_id=? AND id=?", user, session);
    }
    public void trace(String task, int round, Map<String,Object> data) {
        try { jdbc.update("INSERT OR REPLACE INTO agent_context_trace(task_id,round,payload) VALUES(?,?,?)", task, round, json.writeValueAsString(data)); }
        catch (Exception e) { throw new IllegalStateException("无法保存上下文用量", e); }
    }
    public Map<String,Object> latestTrace(String user, String session) {
        var rows = jdbc.queryForList("SELECT t.payload FROM agent_context_trace t JOIN agent_task a ON a.id=t.task_id WHERE a.user_id=? AND a.session_id=? ORDER BY a.updated_at DESC,t.round DESC LIMIT 1", user, session);
        try { return rows.isEmpty() ? Map.of() : json.readValue(rows.get(0).get("payload").toString(), Map.class); }
        catch (Exception e) { throw new IllegalStateException("上下文统计无法读取", e); }
    }
    @org.springframework.transaction.annotation.Transactional
    public void clear(String user, String session) {
        jdbc.update("DELETE FROM agent_context_trace WHERE task_id IN (SELECT id FROM agent_task WHERE user_id=? AND session_id=?)", user, session);
        jdbc.update("DELETE FROM agent_tool_output WHERE task_id IN (SELECT id FROM agent_task WHERE user_id=? AND session_id=?)", user, session);
        jdbc.update("DELETE FROM agent_task WHERE user_id=? AND session_id=?", user, session);
        jdbc.update("DELETE FROM agent_context_image WHERE user_id=? AND session_id=?", user, session);
        jdbc.update("DELETE FROM agent_context_checkpoint WHERE user_id=? AND session_id=?", user, session);
        jdbc.update("DELETE FROM agent_context_message WHERE user_id=? AND session_id=?", user, session);
        jdbc.update("DELETE FROM agent_context_import WHERE user_id=? AND session_id=?", user, session);
        jdbc.update("DELETE FROM agent_context_contract WHERE user_id=? AND session_id=?", user, session);
        // Empty version fences a summary that was already in flight when this session was cleared.
        checkpoint(user, session, 0, "");
    }
}
