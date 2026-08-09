package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.EmailService;
import util.Logger;

import java.util.List;
import java.util.Map;

/**
 * QQ邮箱工具集 — 收件、搜索、查看、发送、回复、删除。
 * 通过 Jakarta Mail 直连 QQ邮箱 IMAP/SMTP。
 */
@Component
public class EmailTools {

    private final EmailService emailService;
    private final Logger logger;

    @Autowired
    public EmailTools(EmailService emailService, Logger logger) {
        this.emailService = emailService;
        this.logger = logger;
    }

    @Tool(description = "列出最近的邮件摘要。用户说看看邮件有新邮件吗时调用。返回发件人、主题、时间、正文预览和邮件编号。")
    public String listRecentMails(
            @ToolParam(description = "要列出的邮件数量，默认10封最多50封") String count) {
        int n = parseInt(count, 10);
        if (n > 50) n = 50;
        List<Map<String, Object>> mails = emailService.listRecent(n);
        if (mails.isEmpty()) {
            return "收件箱是空的~";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近 ").append(mails.size()).append(" 封邮件：\n");
        sb.append("=".repeat(40)).append("\n");
        for (var m : mails) {
            sb.append("【").append(m.get("id")).append("】");
            sb.append(m.get("subject")).append("\n");
            sb.append("  发件人: ").append(m.get("from")).append("\n");
            sb.append("  时间: ").append(m.get("date"));
            if (Boolean.TRUE.equals(m.get("hasAttachment"))) sb.append("  有附件");
            sb.append("\n");
            String preview = (String) m.get("preview");
            if (preview != null && !preview.isBlank()) {
                sb.append("  摘要: ").append(preview).append("\n");
            }
            sb.append("\n");
        }
        sb.append("---\n查看某封邮件内容请用邮件编号调用 getMailDetail。");
        return sb.toString().trim();
    }

    @Tool(description = "按关键词搜索邮件匹配主题和发件人。用户说找一下某某发的邮件时调用。")
    public String searchMails(
            @ToolParam(description = "搜索关键词匹配主题和发件人") String keyword,
            @ToolParam(description = "最多返回几封默认10") String maxResults) {
        if (keyword == null || keyword.isBlank()) {
            return "请提供搜索关键词~";
        }
        int limit = parseInt(maxResults, 10);
        if (limit > 30) limit = 30;
        List<Map<String, Object>> mails = emailService.search(keyword, limit);
        if (mails.isEmpty()) {
            return "没有找到包含 " + keyword + " 的邮件~";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("搜索 ").append(keyword).append(" 找到 ").append(mails.size()).append(" 封：\n");
        sb.append("=".repeat(40)).append("\n");
        for (var m : mails) {
            sb.append("【").append(m.get("id")).append("】");
            sb.append(m.get("subject")).append("\n");
            sb.append("  发件人: ").append(m.get("from")).append("\n");
            sb.append("  时间: ").append(m.get("date")).append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "查看某封邮件的完整内容包括正文和附件信息。需要邮件编号。")
    public String getMailDetail(
            @ToolParam(description = "邮件编号从邮件列表结果中获取的 ID") String mailId) {
        int id = parseInt(mailId, -1);
        if (id <= 0) {
            return "请提供有效的邮件编号~";
        }
        Map<String, Object> detail = emailService.getDetail(id);
        if (detail.containsKey("error")) {
            return (String) detail.get("error");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("邮件: ").append(detail.get("subject")).append("\n");
        sb.append("=".repeat(40)).append("\n");
        sb.append("发件人: ").append(detail.get("from")).append("\n");
        sb.append("时间: ").append(detail.get("date"));
        if (Boolean.TRUE.equals(detail.get("hasAttachment"))) sb.append("  有附件");
        sb.append("\n\n");
        sb.append(detail.get("body"));
        return sb.toString().trim();
    }

    @Tool(description = "发送邮件。用户说帮我发邮件给XX时调用。")
    public String sendMail(
            @ToolParam(description = "收件人邮箱地址") String to,
            @ToolParam(description = "邮件主题") String subject,
            @ToolParam(description = "邮件正文纯文本") String body) {
        if (to == null || to.isBlank()) return "请提供收件人邮箱地址~";
        if (subject == null || subject.isBlank()) subject = "(无主题)";
        if (body == null || body.isBlank()) return "邮件正文不能为空~";

        boolean ok = emailService.send(to.trim(), subject.trim(), body.trim());
        if (ok) {
            logger.log("INFO", "邮件已发送 -> " + to);
            return "邮件已发送给 " + to + "（主题: " + subject + "）";
        }
        return "邮件发送失败，请稍后再试~";
    }

    @Tool(description = "回复某封邮件自动加上 Re 前缀并引用原文。需要邮件编号。")
    public String replyMail(
            @ToolParam(description = "要回复的邮件编号") String mailId,
            @ToolParam(description = "回复正文") String body) {
        int id = parseInt(mailId, -1);
        if (id <= 0) return "请提供有效的邮件编号~";
        if (body == null || body.isBlank()) return "回复正文不能为空~";

        boolean ok = emailService.reply(id, body.trim());
        if (ok) {
            logger.log("INFO", "已回复邮件 #" + id);
            return "已回复邮件 #" + id;
        }
        return "回复失败，请稍后再试~";
    }

    @Tool(description = "删除某封邮件。需要邮件编号。")
    public String deleteMail(
            @ToolParam(description = "要删除的邮件编号") String mailId) {
        int id = parseInt(mailId, -1);
        if (id <= 0) return "请提供有效的邮件编号~";

        boolean ok = emailService.delete(id);
        if (ok) {
            logger.log("INFO", "已删除邮件 #" + id);
            return "已删除邮件 #" + id;
        }
        return "删除失败，请检查邮件编号是否正确~";
    }

    private int parseInt(String s, int defaultValue) {
        try { return Integer.parseInt(s != null ? s.trim() : ""); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
