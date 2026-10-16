package service.agent;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/** Runtime estimate only. Provider usage remains the billing authority. */
public final class ContextTokens {
    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    private ContextTokens() {}
    public static int count(String text) { return ENCODING.countTokensOrdinary(Objects.toString(text, "")); }
    public static int message(Message message) {
        int total = 8 + count(message.getText());
        if (message instanceof AssistantMessage a) for (var call : a.getToolCalls())
            total += 12 + count(call.name()) + count(call.arguments());
        if (message instanceof ToolResponseMessage t) for (var response : t.getResponses())
            total += 12 + count(response.name()) + count(response.responseData());
        // Image costs vary by provider/resolution. Reserve conservatively and label as an estimate.
        if (message instanceof UserMessage u) total += u.getMedia().size() * 4096;
        return total;
    }
    public static int messages(List<Message> messages) { return messages.stream().mapToInt(ContextTokens::message).sum(); }
    public static int tools(List<ToolCallback> tools) {
        return tools.stream().map(ToolCallback::getToolDefinition)
            .mapToInt(d -> 16 + count(d.name()) + count(d.description()) + count(d.inputSchema())).sum();
    }
    public static String head(String text, int tokens) {
        if (tokens <= 0) return "";
        if (count(text) <= tokens) return text;
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (count(text.substring(0, mid)) <= tokens) low = mid; else high = mid - 1;
        }
        if (low > 0 && Character.isHighSurrogate(text.charAt(low - 1))) low--;
        return text.substring(0, low);
    }
    public static String tail(String text, int tokens) {
        if (tokens <= 0) return "";
        if (count(text) <= tokens) return text;
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high) / 2;
            if (count(text.substring(mid)) <= tokens) high = mid; else low = mid + 1;
        }
        if (low < text.length() && Character.isLowSurrogate(text.charAt(low))) low++;
        return text.substring(low);
    }
}
