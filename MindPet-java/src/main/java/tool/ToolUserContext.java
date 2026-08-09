package tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ThreadLocal — 当前调用者的微信用户 ID + 工具调用标记。
 * AiService.chat() 入口设值/清理，@Tool 被调用时自动标记。
 */
public final class ToolUserContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> TOOLS_USED = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<byte[]> IMAGE_DATA = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, List<GeneratedFile>> GENERATED_FILES = new ConcurrentHashMap<>();

    public static void set(String userId) {
        String requestId = REQUEST_ID.get();
        set(userId, SESSION_ID.get());
        if (requestId != null && !requestId.isBlank()) REQUEST_ID.set(requestId);
    }
    public static void set(String userId, String sessionId) {
        CURRENT.set(userId);
        // 没有 sessionId 时生成一个随机 ID，避免所有空会话共用一个 bucket
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId
            : java.util.UUID.randomUUID().toString();
        SESSION_ID.set(sid);
        TOOLS_USED.set(false);
        IMAGE_DATA.remove();
        REQUEST_ID.remove();
    }

    public static String get() { return CURRENT.get(); }
    /** 当前会话ID，每次请求必定有值 */
    public static String getSessionId() {
        String sid = SESSION_ID.get();
        if (sid != null) return sid;
        // 极小概率：有人忘了调 set()，生成临时 ID
        sid = java.util.UUID.randomUUID().toString();
        SESSION_ID.set(sid);
        return sid;
    }

    public static void clear() {
        CURRENT.remove();
        SESSION_ID.remove();
        TOOLS_USED.remove();
        IMAGE_DATA.remove();
        REQUEST_ID.remove();
    }

    public static void markToolsUsed() { TOOLS_USED.set(true); }
    public static boolean isToolsUsed() { return Boolean.TRUE.equals(TOOLS_USED.get()); }

    /** 存储当前请求的图片数据（供工具读取，如发票OCR）。 */
    public static void setImageData(byte[] data) { IMAGE_DATA.set(data); }
    /** 获取当前请求的图片数据。 */
    public static byte[] getImageData() { return IMAGE_DATA.get(); }

    public static void setRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) REQUEST_ID.remove();
        else REQUEST_ID.set(requestId);
    }

    public static String getRequestId() { return REQUEST_ID.get(); }

    public static void addGeneratedFile(String path, String name, String mimeType, String url) {
        String requestId = getRequestId();
        if (requestId == null || requestId.isBlank()) return;
        GENERATED_FILES.computeIfAbsent(requestId, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
            .add(new GeneratedFile(path, name, mimeType, url));
    }

    public static List<GeneratedFile> getGeneratedFiles() {
        String requestId = getRequestId();
        return getGeneratedFiles(requestId);
    }

    public static List<GeneratedFile> getGeneratedFiles(String requestId) {
        if (requestId == null || requestId.isBlank()) return List.of();
        List<GeneratedFile> files = GENERATED_FILES.get(requestId);
        return files == null ? List.of() : List.copyOf(files);
    }

    public static List<GeneratedFile> drainGeneratedFiles(String requestId) {
        if (requestId == null || requestId.isBlank()) return List.of();
        List<GeneratedFile> files = GENERATED_FILES.remove(requestId);
        return files == null ? List.of() : List.copyOf(files);
    }

    /** Returns Markdown only for artifacts that the model did not already include. */
    public static String missingGeneratedFilesMarkdown(String reply) {
        String text = reply == null ? "" : reply;
        StringBuilder markdown = new StringBuilder();
        for (GeneratedFile file : getGeneratedFiles()) {
            if (file.url() == null || file.url().isBlank() || text.contains(file.url())) continue;
            if (markdown.isEmpty()) markdown.append("\n\n");
            if (file.mimeType() != null && file.mimeType().startsWith("image/")) {
                markdown.append("![").append(escapeMarkdownLabel(file.name())).append("](")
                    .append(file.url()).append(")\n");
            } else {
                markdown.append("[").append(escapeMarkdownLabel(file.name())).append("](")
                    .append(file.url()).append(")\n");
            }
        }
        return markdown.toString().stripTrailing();
    }

    private static String escapeMarkdownLabel(String value) {
        return value == null ? "生成文件" : value.replace("[", "（").replace("]", "）");
    }

    public record GeneratedFile(String path, String name, String mimeType, String url) {}

    private ToolUserContext() {}
}
