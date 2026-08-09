package service;

import config.EmailConfig;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * QQ邮箱底层服务 — Jakarta Mail 直连 IMAP（收件）+ SMTP（发件）。
 * 不经过外部 MCP 进程，所有操作在 JVM 内完成。
 */
@Service
public class EmailService {

    private final EmailConfig config;
    private final Logger logger;

    @Autowired
    public EmailService(EmailConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    // ==================== 收件 (IMAP) ====================

    /** 列出最近 N 封邮件（摘要：发件人、主题、时间、正文前150字） */
    public List<Map<String, Object>> listRecent(int count) {
        List<Map<String, Object>> list = new ArrayList<>();
        Store store = null;
        try {
            store = connectImap();
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            int total = inbox.getMessageCount();
            int start = Math.max(1, total - count + 1);
            Message[] messages = inbox.getMessages(start, total);
            // 倒序：最新的在前面
            for (int i = messages.length - 1; i >= 0; i--) {
                list.add(summarize(messages[i], start + i));
            }
            inbox.close(false);
        } catch (Exception e) {
            logger.log("ERROR", "读取邮件列表失败: " + e.getMessage());
        } finally {
            closeStore(store);
        }
        return list;
    }

    /** 按关键词搜索邮件（匹配主题和发件人） */
    public List<Map<String, Object>> search(String keyword, int maxResults) {
        List<Map<String, Object>> list = new ArrayList<>();
        Store store = null;
        try {
            store = connectImap();
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            // 搜索主题或发件人
            SearchTerm term = new OrTerm(
                new SubjectTerm(keyword),
                new FromStringTerm(keyword)
            );
            Message[] messages = inbox.search(term);
            int limit = Math.min(messages.length, maxResults);
            // 倒序：最新的在前面
            for (int i = messages.length - 1; i >= messages.length - limit; i--) {
                list.add(summarize(messages[i], 0));
            }
            inbox.close(false);
        } catch (Exception e) {
            logger.log("ERROR", "搜索邮件失败: " + e.getMessage());
        } finally {
            closeStore(store);
        }
        return list;
    }

    /** 获取邮件完整内容（含纯文本正文 + 附件信息） */
    public Map<String, Object> getDetail(int mailId) {
        Store store = null;
        try {
            store = connectImap();
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message message = inbox.getMessage(mailId);
            Map<String, Object> detail = summarize(message, mailId);
            // 提取完整纯文本正文
            String body = extractPlainText(message);
            detail.put("body", body != null ? body : "(无法读取正文)");
            inbox.close(false);
            return detail;
        } catch (Exception e) {
            logger.log("ERROR", "读取邮件详情失败: " + e.getMessage());
            return Map.of("error", "读取失败: " + e.getMessage());
        } finally {
            closeStore(store);
        }
    }

    /** 删除邮件 */
    public boolean delete(int mailId) {
        Store store = null;
        try {
            store = connectImap();
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            Message message = inbox.getMessage(mailId);
            message.setFlag(Flags.Flag.DELETED, true);
            inbox.close(true); // expunge
            return true;
        } catch (Exception e) {
            logger.log("ERROR", "删除邮件失败: " + e.getMessage());
            return false;
        } finally {
            closeStore(store);
        }
    }

    // ==================== 发件 (SMTP) ====================

    /** 发送纯文本邮件 */
    public boolean send(String to, String subject, String body) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", config.getSmtp().getHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtp().getPort()));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.getUsername(), config.getPassword());
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.getUsername()));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
            msg.setSubject(subject, "UTF-8");
            msg.setText(body, "UTF-8");
            msg.setSentDate(new Date());

            Transport.send(msg);
            logger.log("INFO", "邮件已发送 → " + to + " 主题: " + subject);
            return true;
        } catch (Exception e) {
            logger.log("ERROR", "发送邮件失败: " + e.getMessage());
            return false;
        }
    }

    /** 回复邮件（引用原文） */
    public boolean reply(int mailId, String replyBody) {
        Store store = null;
        try {
            // 先读原邮件信息
            store = connectImap();
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message original = inbox.getMessage(mailId);
            String to = InternetAddress.toString(original.getFrom());
            String origSubject = original.getSubject();
            if (origSubject == null) origSubject = "";
            String reSubject = origSubject.startsWith("Re:") || origSubject.startsWith("RE:")
                ? origSubject : "Re: " + origSubject;

            // 引用原文
            String origBody = extractPlainText(original);
            String quoted = replyBody;
            if (origBody != null && !origBody.isBlank()) {
                quoted += "\n\n--- 原邮件 ---\n";
                for (String line : origBody.split("\n")) {
                    quoted += "> " + line + "\n";
                }
            }

            inbox.close(false);
            closeStore(store);
            store = null;

            return send(to, reSubject, quoted);
        } catch (Exception e) {
            logger.log("ERROR", "回复邮件失败: " + e.getMessage());
            return false;
        } finally {
            closeStore(store);
        }
    }

    // ==================== 内部方法 ====================

    private Store connectImap() throws MessagingException {
        Properties props = new Properties();
        props.put("mail.imap.host", config.getImap().getHost());
        props.put("mail.imap.port", String.valueOf(config.getImap().getPort()));
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(config.getUsername(), config.getPassword());
        return store;
    }

    private void closeStore(Store store) {
        if (store != null) {
            try { store.close(); } catch (Exception ignored) {}
        }
    }

    /** 提取邮件摘要（不含完整正文） */
    private Map<String, Object> summarize(Message msg, int mailId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", mailId);
        try { map.put("from", InternetAddress.toString(msg.getFrom())); }
        catch (Exception e) { map.put("from", "?"); }
        try { map.put("subject", msg.getSubject() != null ? msg.getSubject() : "(无主题)"); }
        catch (Exception e) { map.put("subject", "(无主题)"); }
        try {
            Date sent = msg.getSentDate();
            map.put("date", sent != null ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(sent) : "?");
        } catch (Exception e) { map.put("date", "?"); }
        try { map.put("hasAttachment", hasAttachment(msg)); }
        catch (Exception e) { map.put("hasAttachment", false); }
        // 正文摘要（前150字）
        String preview = extractPlainText(msg);
        if (preview != null && preview.length() > 150) {
            preview = preview.substring(0, 150) + "...";
        }
        map.put("preview", preview != null ? preview : "(无正文)");
        return map;
    }

    /** 提取纯文本正文（HTML→text，不返回大段 HTML 给 LLM） */
    private String extractPlainText(Part part) {
        try {
            Object content = part.getContent();
            if (content instanceof String) {
                String ct = part.getContentType();
                if (ct != null && ct.toLowerCase().contains("text/plain")) {
                    return ((String) content).trim();
                }
                if (ct != null && ct.toLowerCase().contains("text/html")) {
                    return stripHtml((String) content);
                }
                return ((String) content).trim();
            }
            if (content instanceof Multipart) {
                Multipart mp = (Multipart) content;
                // 优先找 text/plain，其次 text/html
                String html = null;
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    String ct = bp.getContentType();
                    if (ct != null && ct.toLowerCase().contains("text/plain")) {
                        String text = extractPlainText(bp);
                        if (text != null && !text.isBlank()) return text;
                    }
                    if (ct != null && ct.toLowerCase().contains("text/html") && html == null) {
                        html = extractPlainText(bp);
                    }
                }
                return html; // fallback to HTML
            }
        } catch (Exception e) {
            // silently ignore unreadable parts
        }
        return null;
    }

    /** 粗糙 HTML→纯文本转换 */
    private String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<br\\s*/?>", "\n")
                   .replaceAll("<[^>]+>", "")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&quot;", "\"")
                   .replaceAll("\\n{3,}", "\n\n")
                   .trim();
    }

    /** 检查是否有附件 */
    private boolean hasAttachment(Part part) {
        try {
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return true;
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    if (hasAttachment(mp.getBodyPart(i))) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
