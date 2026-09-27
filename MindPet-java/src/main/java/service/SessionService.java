package service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** API facade; callers are independent from the selected session database. */
@Service
public class SessionService {
    private final SessionStore store;
    public SessionService(SessionStore store) { this.store = store; }
    public List<Map<String, Object>> listSessions(String userId) { return store.listSessions(userId); }
    public Map<String, Object> upsertSession(String userId, String sessionId, Map<String, Object> updates) { return store.upsertSession(userId, sessionId, updates); }
    public void deleteSession(String userId, String sessionId) { store.deleteSession(userId, sessionId); }
    public void appendMessage(String userId, String sessionId, Map<String, Object> message) { store.appendMessage(userId, sessionId, message); }
    public List<Map<String, Object>> loadMessages(String userId, String sessionId, int limit) { return store.loadMessages(userId, sessionId, limit); }
    public List<Map<String, Object>> loadAllMessages(String userId, int limit) { return store.loadAllMessages(userId, limit); }
    public int markMessagesSummarized(String userId, String sessionId, List<Map<String, Object>> references) { return store.markMessagesSummarized(userId, sessionId, references); }
}
