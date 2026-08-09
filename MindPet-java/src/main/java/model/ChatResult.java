package model;

/**
 * Return type for AiService.chat/chatWithImage — carries the reply text,
 * whether any tools were called, and Token usage statistics.
 *
 * @param reply            LLM reply text (already formatted, ready to send)
 * @param toolsUsed        true if one or more @Tool methods were invoked
 * @param promptTokens     prompt (input) tokens consumed
 * @param completionTokens completion (output) tokens generated
 */
public record ChatResult(String reply, boolean toolsUsed, int promptTokens, int completionTokens) {
    public static ChatResult of(String reply, boolean toolsUsed) {
        return new ChatResult(reply, toolsUsed, 0, 0);
    }

    public static ChatResult of(String reply, boolean toolsUsed, int promptTokens, int completionTokens) {
        return new ChatResult(reply, toolsUsed, promptTokens, completionTokens);
    }

    /** Total tokens = prompt + completion */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
