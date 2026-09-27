package service;

import java.util.List;
import java.util.Map;

/** Storage boundary for desktop sessions. */
public interface SessionStore {
    List<Map<String, Object>> listSessions(String userId);
    Map<String, Object> upsertSession(String userId, String sessionId, Map<String, Object> updates);
    void deleteSession(String userId, String sessionId);
    void appendMessage(String userId, String sessionId, Map<String, Object> message);
    List<Map<String, Object>> loadMessages(String userId, String sessionId, int limit);
    List<Map<String, Object>> loadAllMessages(String userId, int limit);
    int markMessagesSummarized(String userId, String sessionId, List<Map<String, Object>> references);
}
