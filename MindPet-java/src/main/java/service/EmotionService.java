package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import util.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 情感分析 — 独立 LLM 调用 + 语境翻转规则 + 情感历史对比。
 *
 * 三层架构（参考 Giftia EmotionAnalyzer）：
 * 1. 危险信号检测 → 硬编码关键词，不可遗漏
 * 2. LLM 结构化输出 → emotion + intensity + triggers
 * 3. 语境翻转规则 → 纠正 LLM 典型盲区
 *
 * 情感历史追踪：
 * - 每条消息的情感存入 Redis 历史列表，用于趋势对比
 * - 每次分析后对比上次情感，显式输出变化趋势到 system prompt
 */
@Service
public class EmotionService {

    // ==================== 危险关键词 ====================

    private static final List<String> DANGER_KEYWORDS = List.of(
        "想死", "不想活", "自杀", "自残", "结束生命", "活不下去",
        "活着没意思", "不想活了", "死了算了", "跳楼", "割腕"
    );

    // ==================== 语境翻转规则 ====================

    /**
     * 语境翻转规则 — LLM 做情感分析的典型盲区。
     * 每条规则：如果 LLM 判为 fromEmotions 中的某一种，且消息同时匹配 positiveHints 和 negativeHints，
     * 则翻转为 correctEmotion。
     *
     * 参考 Giftia EmotionAnalyzer.CONTEXT_FLIPS。
     */
    private record ContextFlip(
        List<String> fromEmotions,
        String correctEmotion,
        List<String> positiveHints,
        List<String> negativeHints,
        String reason
    ) {}

    private static final List<ContextFlip> CONTEXT_FLIPS = List.of(
        // 1. "喜欢/想/期待…但没结果" → 难过（LLM 看到"喜欢"容易判开心）
        new ContextFlip(
            List.of("happy", "excited", "neutral"),
            "sad",
            List.of("喜欢", "想", "期待", "希望", "好想"),
            List.of("没", "不", "没有", "不了", "不到", "失败", "错过", "失去", "不可能", "拒绝"),
            "表面积极词汇+否定结果 → 实际是难过"
        ),
        // 2. "终于结束了/熬过来了" → 如释重负，不是难过
        new ContextFlip(
            List.of("sad", "stressed", "anxious"),
            "relieved",
            List.of("终于", "总算", "熬过来", "结束了", "过去了", "搞定", "完成了", "解放"),
            List.of(),
            "终于/总算 + 结束语境 → 实际是如释重负"
        ),
        // 3. "算了/随便/没事/无所谓" + 负面词 → 强装镇定，实际难过
        new ContextFlip(
            List.of("neutral", "happy"),
            "sad",
            List.of("算了", "随便", "没事", "无所谓", "就这样吧"),
            List.of("烦", "累", "崩溃", "难受", "痛苦", "想哭", "失望", "心累"),
            "敷衍词汇+高强度负面词 → 强装镇定，实际难过"
        ),
        // 4. "哈哈哈/笑死/绝了" + 负面吐槽 → 苦笑/自嘲，实际压力大
        new ContextFlip(
            List.of("happy", "excited"),
            "stressed",
            List.of("哈哈", "笑死", "搞笑", "绝了", "真行"),
            List.of("无语", "服了", "裂开", "麻了", "离谱", "崩溃"),
            "笑+负面吐槽 → 苦笑/自嘲，实际焦虑"
        ),
        // 5. "一个人/孤独/没人" + 生活场景 → 孤独（不只是中性/难过）
        new ContextFlip(
            List.of("neutral", "sad"),
            "lonely",
            List.of("一个人", "孤独", "没人", "自己", "独自"),
            List.of("吃饭", "看电影", "逛街", "去医院", "过节", "周末", "在家"),
            "独处+生活场景 → 实际是孤独"
        ),
        // 6. "谢谢/感谢/有你" → 感恩（LLM 可能忽略）
        new ContextFlip(
            List.of("neutral", "sad", "anxious"),
            "grateful",
            List.of("谢谢你", "感谢", "有你真好", "多亏你", "幸好有"),
            List.of(),
            "表达感谢 → 实际包含感恩"
        )
    );

    // ==================== LLM Prompt ====================

    private static final String EMOTION_PROMPT = """
        分析以下用户消息的情感状态。返回 JSON：
        {
          "emotion": "happy|sad|anxious|angry|neutral|excited|stressed|relieved|grateful|lonely",
          "intensity": 0.0-1.0,
          "triggers": "触发原因简述（可选）"
        }
        只返回 JSON，不要加任何解释。""";

    // ==================== 情感历史 Redis Key ====================

    private static final String EMOTION_HISTORY_KEY = "emotion:history:";

    private String historyKey(String userId) {
        return EMOTION_HISTORY_KEY + userId + ":" + tool.ToolUserContext.getSessionId();
    }
    private static final Duration HISTORY_TTL = Duration.ofDays(7);
    private static final int HISTORY_MAX_SIZE = 50;

    private final ChatClient.Builder chatClientBuilder;
    private final StringRedisTemplate redis;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public EmotionService(ChatClient.Builder chatClientBuilder, StringRedisTemplate redis, Logger logger) {
        this.chatClientBuilder = chatClientBuilder;
        this.redis = redis;
        this.logger = logger;
    }

    // ==================== 主分析方法 ====================

    /**
     * 分析用户消息的情感。三层架构：
     * 1. 危险信号检测（硬编码）
     * 2. LLM 结构化输出
     * 3. 语境翻转规则纠正
     */
    public EmotionResult analyze(String userMessage) {
        // 第1层：危险信号检测
        String lower = userMessage.toLowerCase();
        for (String kw : DANGER_KEYWORDS) {
            if (lower.contains(kw)) {
                logger.log("WARN", "检测到危险信号: " + kw);
                return new EmotionResult("stressed", 0.9, kw,
                    "⚠️ 检测到危险信号，请优先关注用户安全");
            }
        }

        // 第2层：LLM 情感分析
        EmotionResult llmResult;
        try {
            String result = chatClientBuilder.build()
                .prompt()
                .system(EMOTION_PROMPT)
                .user(userMessage)
                .call()
                .content();

            if (result == null || result.isBlank()) return EmotionResult.NEUTRAL;

            String json = result.trim();
            if (json.startsWith("```")) json = json.replaceAll("```\\w*\\n?", "").replace("```", "").trim();

            Map<String, Object> map = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            llmResult = new EmotionResult(
                String.valueOf(map.getOrDefault("emotion", "neutral")),
                parseDouble(map.getOrDefault("intensity", "0.3")),
                String.valueOf(map.getOrDefault("triggers", "")),
                null
            );
        } catch (Exception e) {
            logger.log("WARN", "情感分析失败: " + e.getMessage());
            return EmotionResult.NEUTRAL;
        }

        // 第3层：语境翻转规则纠正
        return applyContextFlips(userMessage, llmResult);
    }

    /**
     * 对已有的 LLM 情感结果做后处理（危险信号 + 语境翻转），不发起新的 LLM 调用。
     * 用于意图路由与情感分析合并的场景（AiService.preCall 已返回原始情感）。
     */
    public EmotionResult enrich(String userMessage, EmotionResult llmResult) {
        // 第1层：危险信号检测
        String lower = userMessage.toLowerCase();
        for (String kw : DANGER_KEYWORDS) {
            if (lower.contains(kw)) {
                return new EmotionResult("stressed", 0.9, kw,
                    "⚠️ 检测到危险信号，请优先关注用户安全");
            }
        }
        // 第3层：语境翻转规则纠正
        return applyContextFlips(userMessage, llmResult);
    }

    // ==================== 语境翻转 ====================

    /**
     * 应用语境翻转规则，纠正 LLM 的典型盲区。
     * 多条规则可能相继触发，每次翻转后以新情绪继续匹配后续规则。
     */
    private EmotionResult applyContextFlips(String message, EmotionResult llmResult) {
        String lower = message.toLowerCase();
        EmotionResult current = llmResult;

        for (ContextFlip flip : CONTEXT_FLIPS) {
            if (!flip.fromEmotions.contains(current.emotion)) continue;

            boolean hasPositive = flip.positiveHints.isEmpty()
                || flip.positiveHints.stream().anyMatch(lower::contains);
            boolean hasNegative = flip.negativeHints.isEmpty()
                || flip.negativeHints.stream().anyMatch(lower::contains);

            if (hasPositive && hasNegative) {
                logger.log("INFO", "语境翻转: " + current.emotion + " → " + flip.correctEmotion
                    + " | " + flip.reason + " | \"" + message.substring(0, Math.min(40, message.length())) + "\"");
                String mergedTriggers = current.triggers.isBlank()
                    ? flip.reason
                    : current.triggers + "（翻转: " + flip.reason + "）";
                current = new EmotionResult(flip.correctEmotion, current.intensity, mergedTriggers,
                    current.alert);
            }
        }

        return current;
    }

    // ==================== 情感历史追踪 ====================

    /**
     * 保存情感记录到 Redis 历史列表。
     */
    public void saveToHistory(String userId, EmotionResult result) {
        if (result == null) return;
        try {
            Map<String, Object> entry = Map.of(
                "emotion", result.emotion,
                "intensity", result.intensity,
                "triggers", result.triggers,
                "timestamp", System.currentTimeMillis()
            );
            String key = historyKey(userId);
            redis.opsForList().rightPush(key, mapper.writeValueAsString(entry));
            redis.opsForList().trim(key, -HISTORY_MAX_SIZE, -1);
            redis.expire(key, HISTORY_TTL);
        } catch (Exception e) {
            // 静默降级
        }
    }

    /**
     * 获取最近一次情感记录。
     */
    private EmotionResult getLastEmotion(String userId) {
        try {
            String key = historyKey(userId);
            String json = redis.opsForList().index(key, -1);
            if (json == null || json.isBlank()) return null;
            Map<String, Object> entry = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return new EmotionResult(
                String.valueOf(entry.getOrDefault("emotion", "neutral")),
                parseDouble(entry.getOrDefault("intensity", "0.3")),
                String.valueOf(entry.getOrDefault("triggers", "")),
                null
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对比当前情感与上次情感，生成显式趋势描述。
     * @return 趋势文本，如 "⚠️ 用户情绪从开心转为难过，可能需要关注"；无变化时返回空字符串
     */
    public String getEmotionTrend(String userId, EmotionResult current) {
        if (current == null || current.alert != null) return "";

        try {
            EmotionResult last = getLastEmotion(userId);
            if (last == null) return "";

            // 同一情绪但强度显著上升
            if (last.emotion.equals(current.emotion)) {
                if (current.intensity - last.intensity >= 0.3) {
                    return "用户情绪持续" + emotionLabel(current.emotion)
                        + "且强度上升（" + String.format("%.1f", last.intensity)
                        + " → " + String.format("%.1f", current.intensity) + "）";
                }
                return "";
            }

            // 情绪变化
            String from = emotionLabel(last.emotion);
            String to = emotionLabel(current.emotion);

            if (isWorsening(last.emotion, current.emotion)) {
                return "⚠️ 用户情绪从" + from + "转为" + to + "，可能需要关注";
            } else {
                return "用户情绪从" + from + "转为" + to;
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 情绪优先级 ====================

    private boolean isWorsening(String from, String to) {
        Map<String, Integer> negativity = Map.of(
            "excited", 1, "happy", 1, "grateful", 1,
            "relieved", 2, "neutral", 3,
            "anxious", 5, "stressed", 5, "sad", 6,
            "lonely", 7, "angry", 7
        );
        return negativity.getOrDefault(to, 3) > negativity.getOrDefault(from, 3);
    }

    // ==================== 工具方法 ====================

    private double parseDouble(Object obj) {
        try { return Double.parseDouble(String.valueOf(obj)); }
        catch (Exception e) { return 0.3; }
    }

    static String emotionLabel(String emotion) {
        return switch (emotion) {
            case "happy" -> "开心";
            case "sad" -> "难过";
            case "anxious" -> "焦虑";
            case "angry" -> "生气";
            case "excited" -> "兴奋";
            case "stressed" -> "压力大";
            case "relieved" -> "如释重负";
            case "grateful" -> "感恩";
            case "lonely" -> "孤独";
            default -> "平静";
        };
    }

    public record EmotionResult(
        String emotion, double intensity, String triggers,
        String alert
    ) {
        public static final EmotionResult NEUTRAL = new EmotionResult("neutral", 0.3, "", null);

        /** 生成注入 system prompt 的情感上下文。 */
        public String toPromptContext() {
            if (alert != null) return alert;
            if ("neutral".equals(emotion) && intensity < 0.5) return "";
            return "用户当前情绪: " + emotionLabel(emotion) + " (强度" + String.format("%.1f", intensity) + ")"
                + (triggers.isBlank() ? "" : ", 原因: " + triggers);
        }

        /** 生成中文标签，用于存入 Redis 消息记录。 */
        public String toTag() {
            if ("neutral".equals(emotion) && intensity < 0.5) return "";
            return emotionLabel(emotion);
        }
    }
}
