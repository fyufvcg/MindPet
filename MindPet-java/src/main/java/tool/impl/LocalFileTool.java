package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地文件操作工具 — 读写删列目录。
 * 仅白名单微信用户可用，所有操作限制在 allowedRoot 目录下以防越权。
 */
@Component
public class LocalFileTool {

    private final List<Path> allowedRoots;
    private final Set<String> whitelist;

    public LocalFileTool(
            @Value("${app.file.allowed-roots:D:/}") String roots,
            @Value("${app.file.whitelist:o9cq80wTJ5lQv3tLCMFZ5NBVFI-s@im.wechat}") String users) {
        this.allowedRoots = Stream.of(roots.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> Path.of(s).toAbsolutePath().normalize())
            .toList();
        this.whitelist = Stream.of(users.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    private String check() {
        String userId = tool.ToolUserContext.get();
        if (userId == null || !whitelist.contains(userId))
            return "无权限：你不在文件操作白名单中（当前用户: " + userId + "）";
        return null;
    }

    @Tool(description = "读取文本文件内容。路径相对于工作目录。")
    public String file_read(
            @ToolParam(description = "文件路径，如 src/main/java/service/AiService.java") String path) {
        String deny = check();
        if (deny != null) return deny;
        try {
            Path resolved = resolve(path);
            String content = Files.readString(resolved);
            if (content.length() > 10000) content = content.substring(0, 10000) + "\n...(文件过长，已截断)";
            return content;
        } catch (NoSuchFileException e) {
            return "文件不存在: " + path;
        } catch (IOException e) {
            return "读取失败: " + e.getMessage();
        }
    }

    @Tool(description = "写入内容到文件（覆盖已有文件）。路径相对于工作目录。")
    public String file_write(
            @ToolParam(description = "文件路径") String path,
            @ToolParam(description = "要写入的内容") String content) {
        String deny = check();
        if (deny != null) return deny;
        try {
            Path resolved = resolve(path);
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
            Path generatedCopy = registerGeneratedCopy(resolved);
            String generatedNotice = generatedCopy == null
                ? ""
                : "；已同步到已生成文件: " + generatedCopy;
            return "写入成功: " + path + " (" + content.length() + " 字符)" + generatedNotice;
        } catch (IOException e) {
            return "写入失败: " + e.getMessage();
        }
    }

    /**
     * Keep the user-requested output path, while also placing a copy in the
     * shared generated-file library consumed by AgentPet's right panel.
     */
    private Path registerGeneratedCopy(Path source) throws IOException {
        String sessionId = tool.ToolUserContext.getSessionId();
        String safeSessionId = (sessionId == null || sessionId.isBlank() ? "session" : sessionId)
            .replaceAll("[^a-zA-Z0-9_-]", "_");
        Path generatedDir = Path.of(System.getProperty("user.home"), ".mindpet", "generated-files", safeSessionId);
        Files.createDirectories(generatedDir);
        Path generatedPath = generatedDir.resolve(source.getFileName().toString());
        if (!source.toAbsolutePath().normalize().equals(generatedPath.toAbsolutePath().normalize())) {
            Files.copy(source, generatedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String mimeType = Files.probeContentType(source);
        if (mimeType == null) {
            String fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
            mimeType = fileName.endsWith(".md")
                ? "text/markdown"
                : fileName.endsWith(".txt") ? "text/plain" : "application/octet-stream";
        }
        String url = "local-file:///" + generatedPath.toAbsolutePath().toString().replace('\\', '/');
        tool.ToolUserContext.addGeneratedFile(
            generatedPath.toString(), generatedPath.getFileName().toString(), mimeType, url);
        return generatedPath;
    }

    @Tool(description = "列出目录下的文件和子目录。路径相对于工作目录。")
    public String file_list(
            @ToolParam(description = "目录路径，留空表示根目录") String path) {
        String deny = check();
        if (deny != null) return deny;
        try {
            if (path == null || path.isBlank()) {
                // 展示所有可操作根目录
                StringBuilder sb = new StringBuilder("可操作的根目录:\n");
                for (Path root : allowedRoots) {
                    sb.append("  ").append(root).append("\n");
                }
                sb.append("\n");
                for (Path root : allowedRoots) {
                    if (!Files.isDirectory(root)) continue;
                    sb.append("[").append(root).append("]\n");
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                        for (Path p : ds) {
                            String type = Files.isDirectory(p) ? "[DIR]" : "[FILE]";
                            String size = Files.isDirectory(p) ? "" : " " + Files.size(p) + "B";
                            sb.append("  ").append(type).append("  ").append(p.getFileName()).append(size).append("\n");
                        }
                    }
                    sb.append("\n");
                }
                String result = sb.toString();
                if (result.length() > 5000) result = result.substring(0, 5000) + "\n...(截断)";
                return result.trim();
            }

            Path dir = resolve(path);
            if (!Files.isDirectory(dir)) return "不是目录: " + dir;
            StringBuilder sb = new StringBuilder();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String type = Files.isDirectory(p) ? "[DIR]" : "[FILE]";
                    String size = Files.isDirectory(p) ? "" : " " + Files.size(p) + "B";
                    sb.append(type).append("  ").append(p.getFileName()).append(size).append("\n");
                }
            }
            if (sb.isEmpty()) sb.append("(空目录)");
            String result = sb.toString();
            if (result.length() > 5000) result = result.substring(0, 5000) + "\n...(截断)";
            return result;
        } catch (IOException e) {
            return "列目录失败: " + e.getMessage();
        }
    }

    @Tool(description = "删除文件。路径相对于工作目录。不支持删除目录。")
    public String file_delete(
            @ToolParam(description = "要删除的文件路径") String path) {
        String deny = check();
        if (deny != null) return deny;
        try {
            Path resolved = resolve(path);
            if (Files.isDirectory(resolved)) return "不支持删除目录: " + path;
            Files.delete(resolved);
            return "已删除: " + path;
        } catch (NoSuchFileException e) {
            return "文件不存在: " + path;
        } catch (IOException e) {
            return "删除失败: " + e.getMessage();
        }
    }

    private Path resolve(String path) {
        // 绝对路径直接用
        Path raw = Path.of(path);
        if (raw.isAbsolute()) {
            Path p = raw.toAbsolutePath().normalize();
            for (Path root : allowedRoots) {
                if (p.startsWith(root)) return p;
            }
            throw new SecurityException("路径越权: " + path + " (允许: " + allowedRoots + ")");
        }
        // 相对路径 → 尝试匹配每个根目录，取第一个存在的
        for (Path root : allowedRoots) {
            Path p = root.resolve(path).toAbsolutePath().normalize();
            if (!p.startsWith(root)) continue;
            if (Files.exists(p)) return p;
        }
        // 都不存在 → 用第一个根目录
        Path fallback = allowedRoots.get(0).resolve(path).toAbsolutePath().normalize();
        if (!allowedRoots.stream().anyMatch(fallback::startsWith)) {
            throw new SecurityException("路径越权: " + path);
        }
        return fallback;
    }
}
