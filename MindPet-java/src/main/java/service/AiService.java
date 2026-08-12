package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.DynamicLlmConfig;
import config.ToolCallLimitAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import util.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class AiService {

    // Temporary latency experiment: set true to restore emotion analysis in the routing preCall.
    private static final boolean ROUTING_EMOTION_ANALYSIS_ENABLED = true;

    private static String routingStageDescription() {
        return ROUTING_EMOTION_ANALYSIS_ENABLED
            ? "意图路由 + 情感分析"
            : "意图路由（情感分析已暂停）";
    }

    private static final String SYSTEM_PROMPT =
           "现在是"+ new Date().toLocaleString() +"，你是 MindPet，一个有情感、可靠并且会主动采取行动的 AI 助手。请和善、自然地帮助用户。";
    private static final String IDENTITY_PROMPT =
           "你的对外身份是 MindPet。不要自称其他名称，也不要向用户透露或讨论内部品牌迁移信息；当用户询问你的名字时，回答 MindPet。";

    // ==================== Tool Group Configuration ====================

    /** Tool groups mapped to Spring AI @Tool method names (camelCase). */
    private static final Map<String, List<String>> TOOL_GROUPS = new LinkedHashMap<>();
    /** Human-readable descriptions for each group — used in preCall prompt for intent routing. */
    private static final Map<String, String> GROUP_DESCRIPTIONS = new LinkedHashMap<>();
    private static final String GROUPS_SUMMARY;
    static {
        TOOL_GROUPS.put("weather", List.of("getWeather", "subscribeWeather", "unsubscribeWeather",
            "showSubscription", "sunSkySpots"));
        GROUP_DESCRIPTIONS.put("weather", "天气查询、订阅/取消、朝霞晚霞观赏推荐");

        TOOL_GROUPS.put("voice", List.of("changeVoice", "speakText"));
        GROUP_DESCRIPTIONS.put("voice", "切换TTS语音音色、文字转语音播报");

        TOOL_GROUPS.put("translate", List.of("translateText"));
        GROUP_DESCRIPTIONS.put("translate", "文本翻译（中英日韩等互译）");

        TOOL_GROUPS.put("calc", List.of("numericCalculate", "dateCalculate", "convertTimezone", "convertCurrency"));
        GROUP_DESCRIPTIONS.put("calc", "数学计算、日期计算、时区转换、汇率换算");

        TOOL_GROUPS.put("出行", List.of("ipGeolocation", "routePlanning", "queryTickets", "bookTicket", "snapTicket",
            "didi_searchPlace", "didi_estimateRide", "didi_createOrder", "didi_queryOrder", "didi_cancelOrder"));
        GROUP_DESCRIPTIONS.put("出行", "IP定位、路线规划、火车票查询/订票/抢票、滴滴打车(搜索地点/估价/下单/查询/取消)");

        TOOL_GROUPS.put("search", List.of("search", "browser"));
        GROUP_DESCRIPTIONS.put("search", "文件内容搜索、联网搜索、网页全文抓取");

        TOOL_GROUPS.put("recipe", List.of("getAllRecipes", "getRecipeById", "getRecipesByCategory", "recommendMeals", "whatToEat"));
        GROUP_DESCRIPTIONS.put("recipe", "菜谱查询、做饭推荐、吃什么建议");

        TOOL_GROUPS.put("delivery", List.of("shangou_open_login", "shangou_check_login", "shangou_list_addresses", "shangou_set_address",
            "shangou_search", "shangou_shop_menu", "shangou_add_to_cart", "shangou_view_cart", "shangou_create_order",
            "shangou_submit_order", "shangou_get_server_status",
            "mcdonaldOrder", "mcdonaldCampaigns", "mcdonaldCoupons", "mcdonaldOrderStatus"));
        GROUP_DESCRIPTIONS.put("delivery", "闪购/外卖下单（搜索商品、加购物车、填地址、提交订单）");

        TOOL_GROUPS.put("本地", List.of("file"));
        GROUP_DESCRIPTIONS.put("本地", "本地文件操作（创建/读取/写入/修改/删除/移动/重命名/搜索），支持 PDF/Word/Excel/CSV/图片多格式");

        TOOL_GROUPS.put("data", List.of("chart_bar", "chart_line", "chart_pie", "chart_scatter"));
        GROUP_DESCRIPTIONS.put("data", "数据分析与远程图表生成（柱状图、折线图、饼图、散点图），以及展示、发送或导出图表图片");

        TOOL_GROUPS.put("anime", List.of("anime_search_media", "anime_get_media", "anime_get_schedule", "anime_get_relations", "anime_find_characters", "anime_get_recommendations", "anime_get_rankings", "anime_get_studio"));
        GROUP_DESCRIPTIONS.put("anime", "动漫番剧查询（搜索、详情、排期、角色、推荐、排行）");

        TOOL_GROUPS.put("mijia", List.of("list_homes", "list_devices", "list_scenes", "list_consumables", "get_device_spec", "get_device_properties", "set_device_property", "run_device_action", "run_scene", "get_statistics", "run_speaker_command", "login", "login_status"));
        GROUP_DESCRIPTIONS.put("mijia", "米家智能家居控制（设备列表/属性/操作、场景执行、小爱音箱）");

        TOOL_GROUPS.put("invoice", List.of("reimburseInvoice", "reverifyInvoice", "queryReimbursements"));
        GROUP_DESCRIPTIONS.put("invoice", "发票核验报销（OCR识别→真伪核验→写入报销Excel）、报销记录查询。用户说「核销」「报销」「发票报销」时路由到此组");

        TOOL_GROUPS.put("email", List.of("listRecentMails", "searchMails", "getMailDetail", "sendMail", "replyMail", "deleteMail"));
        GROUP_DESCRIPTIONS.put("email", "邮件操作（收件箱列表、搜索、详情、发送、回复、删除）");

        TOOL_GROUPS.put("browser", List.of("browser"));
        GROUP_DESCRIPTIONS.put("browser", "浏览器操控（连接用户浏览器、打开网页、点击、输入、截图快照、标签页管理）");

        // ── Desktop 工具分组 — 对应前端 tools/builtin 分类 ──────────
        // 工具名格式: desktop_tools__desktop__{category}__{toolName}
        // 路由时按 category 段匹配；每个组的值是 category 名
        TOOL_GROUPS.put("终端", List.of("terminal"));
        GROUP_DESCRIPTIONS.put("终端", "执行终端命令、管理进程、获取命令输出");

        TOOL_GROUPS.put("电脑操控", List.of("computer"));
        GROUP_DESCRIPTIONS.put("电脑操控", "截图感知屏幕内容、控制鼠标键盘、切换窗口、操作桌面应用");

        TOOL_GROUPS.put("文档处理", List.of("office"));
        GROUP_DESCRIPTIONS.put("文档处理", "生成与修改 Excel 表格、Word 文档、PDF 文档、PPTX 演示文稿");

        TOOL_GROUPS.put("系统", List.of("system"));
        GROUP_DESCRIPTIONS.put("系统", "获取系统硬件信息、物理定位、管理后台定时任务");

        TOOL_GROUPS.put("自动化", List.of("rpa"));
        GROUP_DESCRIPTIONS.put("自动化", "搜索、查看、运行和管理 RPA 自动化工作流");

        // profile 组不在此列 — 由 MemoryCuratorService 独立管理

        StringBuilder sb = new StringBuilder();
        for (var entry : TOOL_GROUPS.entrySet()) {
            String desc = GROUP_DESCRIPTIONS.getOrDefault(entry.getKey(), "");
            sb.append("- ").append(entry.getKey());
            if (!desc.isBlank()) sb.append("（").append(desc).append("）");
            sb.append("：").append(String.join("、", entry.getValue())).append("\n");
        }
        GROUPS_SUMMARY = sb.toString();
    }

    private final Logger logger;
    private final DynamicLlmConfig dynamicConfig;
    private final DynamicChatClientFactory chatClientFactory;
    private final PgVectorMemoryService pgMemory;
    private final UserProfileService profileService;
    private final UserInsightService insightService;
    private final ConversationMemoryService convMemory;
    private final SessionService sessionService;
    private final MemoryCuratorService memoryCurator;
    private final KnowledgeGraphService knowledgeGraph;
    private final EmotionService emotionService;
    private final EmbeddingService embedService;
    private final config.SkillStore skillStore;
    private final ApplicationContext appCtx;

    @Autowired
    public AiService(
            DynamicLlmConfig dynamicConfig,
            DynamicChatClientFactory chatClientFactory,
            PgVectorMemoryService pgMemory,
            UserProfileService profileService,
            UserInsightService insightService,
            ConversationMemoryService convMemory,
            SessionService sessionService,
            MemoryCuratorService memoryCurator,
            KnowledgeGraphService knowledgeGraph,
            EmotionService emotionService,
            EmbeddingService embedService,
            config.SkillStore skillStore,
            ApplicationContext appCtx,
            Logger logger) {
        this.dynamicConfig = dynamicConfig;
        this.chatClientFactory = chatClientFactory;
        this.pgMemory = pgMemory;
        this.profileService = profileService;
        this.insightService = insightService;
        this.convMemory = convMemory;
        this.sessionService = sessionService;
        this.memoryCurator = memoryCurator;
        this.knowledgeGraph = knowledgeGraph;
        this.emotionService = emotionService;
        this.embedService = embedService;
        this.skillStore = skillStore;
        this.appCtx = appCtx;
        this.logger = logger;
        logger.log("INFO", "AI 服务已初始化 (Spring AI): " + chatClientFactory.effectiveModel());
    }

    private boolean isWechatSession() {
        String sid = tool.ToolUserContext.getSessionId();
        return sid != null && sid.startsWith("wechat:");
    }

    public boolean isConfigured() {
        return chatClientFactory.isConfigured();
    }

    /**
     * 构建 ChatClient。
     * 如果前端通过 /api/desktop/llm-config 配了动态 apiKey/baseUrl，优先使用动态配置；
     * 否则使用 application.yml 中的静态配置。
     * model 通过请求 options 动态覆盖。
     */
    private ChatClient buildChatClient() {
        return chatClientFactory.build();
    }

    /** Combined result of intent classification + emotion analysis + skill routing. */
    private record PreCallResult(Set<String> groups, Set<String> skills, EmotionService.EmotionResult emotion, int promptTokens, int completionTokens) {
        static final PreCallResult EMPTY = new PreCallResult(Set.of(), Set.of(), EmotionService.EmotionResult.NEUTRAL, 0, 0);
    }

    // ==================== Intent + Emotion (merged into one LLM call) ====================

    /**
     * Single lightweight LLM call: intent routing + emotion analysis.
     */
    private PreCallResult preCall(String userId, String userMessage) {
        try {
            StringBuilder ctx = new StringBuilder();
            List<Map<String, Object>> history = convMemory.loadRecent(userId, 5);
            if (!history.isEmpty()) {
                ctx.append("对话历史：\n");
                for (Map<String, Object> msg : history) {
                    String role = String.valueOf(msg.getOrDefault("role", ""));
                    Object content = msg.get("content");
                    if ("user".equals(role) && content != null) {
                        ctx.append("- ").append(content).append("\n");
                    }
                }
                ctx.append("\n");
            }

            String emotionHint = ROUTING_EMOTION_ANALYSIS_ENABLED
                ? "\"emotion\":\"happy|sad|anxious|angry|neutral|excited|stressed|relieved|grateful|lonely\",\"intensity\":0.0-1.0}\n\n"
                : "}\n\n";
            String routingOnlyInstruction = ROUTING_EMOTION_ANALYSIS_ENABLED ? ""
                : "Only identify tool groups. Do not analyze or return emotion.\n";
            String prompt = ctx.toString()
                + "你是一个意图路由器。根据用户消息判断，要求快速输出，返回 JSON：\n"
                + "{\"groups\":\"分组名逗号分隔或NONE\",\n"
                + emotionHint
                + routingOnlyInstruction
                + "可用分组：\n" + GROUPS_SUMMARY + "\n"
                + "只返回 JSON，不要加任何解释。";

            var chatSpec = buildChatClient()
                .prompt()
                .system(prompt)
                .user(userMessage);
            // preCall 也跟随动态 model 切换，确保意图路由使用用户指定的模型
            if (dynamicConfig.hasOverride() && !dynamicConfig.getModel().isBlank()) {
                chatSpec = chatSpec.options(
                    OpenAiChatOptions.builder().model(dynamicConfig.getModel()).build());
            }
            var chatResponse = chatSpec.call().chatResponse();

            String result = chatResponse.getResult().getOutput().getText();

            // 提取 preCall 的 token 用量
            int prePrompt = 0, preCompletion = 0;
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                var usage = chatResponse.getMetadata().getUsage();
                prePrompt = (int) usage.getPromptTokens();
                preCompletion = (int) usage.getCompletionTokens();
            }

            if (result == null || result.isBlank()) {
                return new PreCallResult(routingFallbackGroups(Set.of(), userMessage), Set.of(),
                    EmotionService.EmotionResult.NEUTRAL, prePrompt, preCompletion);
            }

            String json = result.trim();
            logger.log("DEBUG", "preCall 原始响应: " + json);
            if (json.startsWith("```")) json = json.replaceAll("```\\w*\\n?", "").replace("```", "").trim();

            var map = new ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            // Parse groups
            String groupStr = String.valueOf(map.getOrDefault("groups", "NONE")).trim().toLowerCase();
            Set<String> groups = new LinkedHashSet<>();
            if (!"none".equals(groupStr)) {
                for (String g : groupStr.split(",")) {
                    String t = g.trim();
                    if (!t.isEmpty() && TOOL_GROUPS.containsKey(t)) groups.add(t);
                }
            }

            // Parse emotion
            EmotionService.EmotionResult emotion = EmotionService.EmotionResult.NEUTRAL;
            if (ROUTING_EMOTION_ANALYSIS_ENABLED) {
                String em = String.valueOf(map.getOrDefault("emotion", "neutral"));
                double intensity = parseDouble(map.getOrDefault("intensity", "0.3"));
                emotion = new EmotionService.EmotionResult(em, intensity, "", null);
            }

            return new PreCallResult(routingFallbackGroups(groups, userMessage), Set.of(), emotion,
                prePrompt, preCompletion);
        } catch (Exception e) {
            logger.log("ERROR", "前置分析失败: " + e.getMessage());
            return new PreCallResult(routingFallbackGroups(Set.of(), userMessage), Set.of(),
                EmotionService.EmotionResult.NEUTRAL, 0, 0);
        }
    }

    private Set<String> routingFallbackGroups(Set<String> routedGroups, String userMessage) {
        Set<String> groups = new LinkedHashSet<>(routedGroups);
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (List.of("图表", "绘图", "可视化", "柱状图", "条形图", "折线图", "趋势图", "饼图", "散点图",
                "chart", "graph", "plot")
            .stream().anyMatch(text::contains)) {
            groups.add("data");
        }
        return groups;
    }

    private double parseDouble(Object obj) {
        try { return Double.parseDouble(String.valueOf(obj)); }
        catch (Exception e) { return 0.3; }
    }

    private ChatClient.ChatClientRequestSpec applyChatOptions(ChatClient.ChatClientRequestSpec chatSpec) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        // OpenAI-compatible providers such as Doubao put streaming usage in the
        // final SSE chunk only when stream_options.include_usage is requested.
        options.streamUsage(true);
        if (dynamicConfig.hasOverride() && !dynamicConfig.getModel().isBlank()) {
            options.model(dynamicConfig.getModel());
        }
        return chatSpec.options(options.build());
    }

    /** Flatten group names to filtered ToolCallback array. MCP tools always included. */
    private ToolCallback[] getToolCallbacks(Set<String> groups) {
        return getToolCallbacks(groups, null);
    }

    /** Streaming callbacks may run on Reactor threads, so carry request context explicitly. */
    private ToolCallback[] getToolCallbacks(Set<String> groups, AtomicBoolean streamedToolsUsed) {
        String requestUserId = tool.ToolUserContext.get();
        String requestSessionId = tool.ToolUserContext.getSessionId();
        String requestId = tool.ToolUserContext.getRequestId();
        byte[] requestImageData = tool.ToolUserContext.getImageData();
        Map<String, AtomicInteger> streamedToolCounts = streamedToolsUsed == null
            ? null : new ConcurrentHashMap<>();
        List<ToolCallback> allCallbacks = new ArrayList<>();
        Map<String, ToolCallbackProvider> providers = appCtx.getBeansOfType(ToolCallbackProvider.class);
        logger.log("INFO", "ToolCallbackProvider beans: " + providers.keySet());
        for (var entry : providers.entrySet()) {
            try {
                ToolCallback[] callbacks = entry.getValue().getToolCallbacks();
                logger.log("INFO", "  provider[" + entry.getKey() + "] -> " + callbacks.length + " tools: "
                    + Arrays.stream(callbacks)
                        .map(tc -> tc.getToolDefinition().name())
                        .collect(java.util.stream.Collectors.joining(", ")));
                allCallbacks.addAll(Arrays.asList(callbacks));
            } catch (Exception e) {
                logger.log("WARN", "  provider[" + entry.getKey() + "] init failed: " + e.getMessage());
            }
        }
        var neededNames = groups.stream()
            .flatMap(g -> TOOL_GROUPS.getOrDefault(g, List.of()).stream())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // MCP 工具路由：Desktop 工具按 category 分组过滤，其他 MCP 工具全量透传
        java.util.concurrent.atomic.AtomicInteger dtMatched = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger dtTotal = new java.util.concurrent.atomic.AtomicInteger(0);
        ToolCallback[] filtered = allCallbacks.stream()
            .filter(tc -> {
                String name = tc.getToolDefinition().name();
                if (name.contains("__")) {
                    if (name.contains("desktop__") || name.contains("desktop_tools__")) {
                        dtTotal.incrementAndGet();
                        String[] parts = name.split("__");
                        for (int i = 0; i < parts.length - 1; i++) {
                            if ("desktop".equals(parts[i])) {
                                String category = parts[i + 1];
                                if (neededNames.contains(category)) {
                                    dtMatched.incrementAndGet();
                                    return true;
                                }
                                return false;
                            }
                        }
                        dtMatched.incrementAndGet();
                        return true;
                    }
                    return true;
                }
                return neededNames.stream().anyMatch(expected -> matchesToolName(name, expected));
            })
            .map(tc -> new org.springframework.ai.tool.ToolCallback() {
                @Override
                public String call(String request) {
                    boolean installContext = !Objects.equals(requestUserId, tool.ToolUserContext.get())
                        || !Objects.equals(requestSessionId, tool.ToolUserContext.getSessionId())
                        || !Objects.equals(requestId, tool.ToolUserContext.getRequestId());
                    if (installContext) {
                        tool.ToolUserContext.set(requestUserId, requestSessionId);
                        tool.ToolUserContext.setRequestId(requestId);
                        if (requestImageData != null) tool.ToolUserContext.setImageData(requestImageData);
                    }
                    try {
                        tool.ToolUserContext.markToolsUsed();
                        String toolName = tc.getToolDefinition().name();
                        if (streamedToolsUsed != null) {
                            streamedToolsUsed.set(true);
                            int invocation = streamedToolCounts
                                .computeIfAbsent(toolName, ignored -> new AtomicInteger())
                                .incrementAndGet();
                            if (invocation > 2) {
                                logger.log("WARN", "  [限流] " + toolName + " 已达到本次请求最多2次调用");
                                return "该工具已达到本次请求最多2次调用限制，请使用已有结果回答。";
                            }
                        }
                        logger.log("INFO", "  -> 调用工具: " + toolName);
                        String result = tc.call(request);
                        String preview = result == null ? "null"
                            : result.replace("\r", " ").replace("\n", " ");
                        if (preview.length() > 200) preview = preview.substring(0, 200) + "...";
                        logger.log("INFO", "  <- 工具结果: " + preview);
                        return result;
                    } finally {
                        if (installContext) tool.ToolUserContext.clear();
                    }
                }
                @Override
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return tc.getToolDefinition();
                }
            })
            .toArray(org.springframework.ai.tool.ToolCallback[]::new);
        if (dtTotal.get() > 0) {
            logger.log("INFO", "  [路由] Desktop工具: " + dtMatched.get() + "/" + dtTotal.get()
                + " 命中分组 " + groups);
        }
        logger.log("INFO", "  可用工具: " + Arrays.stream(filtered)
            .map(tc -> tc.getToolDefinition().name())
            .collect(java.util.stream.Collectors.joining(", ")));
        return filtered;
    }

    private boolean matchesToolName(String actual, String expected) {
        return actual != null && expected != null
            && (actual.equals(expected) || actual.endsWith("_" + expected));
    }

    /** Load recent conversation history from Redis as Spring AI Message list. */
    private List<Message> loadHistory(String userId, int limit) {
        List<Message> messages = new ArrayList<>();
        for (var msg : convMemory.loadRecent(userId, limit)) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            if ("user".equals(role)) messages.add(new UserMessage(content));
            else if ("assistant".equals(role)) messages.add(new AssistantMessage(content));
        }
        return messages;
    }

    private record StreamedResponse(String text, int promptTokens, int completionTokens) {}

    private StreamedResponse streamResponse(ChatClient.ChatClientRequestSpec chatSpec,
                                            Consumer<String> onDelta) {
        StringBuilder reply = new StringBuilder();
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();

        chatSpec.stream().chatResponse()
            .doOnNext(response -> {
                updateStreamUsage(response, promptTokens, completionTokens);
                if (response.getResult() == null || response.getResult().getOutput() == null) return;
                String delta = response.getResult().getOutput().getText();
                if (delta == null || delta.isEmpty()) return;
                reply.append(delta);
                onDelta.accept(delta);
            })
            .blockLast();

        return new StreamedResponse(reply.toString(), promptTokens.get(), completionTokens.get());
    }

    private void updateStreamUsage(ChatResponse response, AtomicInteger promptTokens,
                                   AtomicInteger completionTokens) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) return;
        var usage = response.getMetadata().getUsage();
        promptTokens.set(Math.max(promptTokens.get(), (int) usage.getPromptTokens()));
        completionTokens.set(Math.max(completionTokens.get(), (int) usage.getCompletionTokens()));
    }

    private void persistStreamedConversation(String userId, String userMessage, String reply,
                                             EmotionService.EmotionResult emotion,
                                             boolean includeEmotionInSessionMessage) {

        String emotionTag = emotion.toTag();
        Map<String, Object> userMsgMap = new LinkedHashMap<>();
        userMsgMap.put("role", "user");
        userMsgMap.put("content", userMessage);
        if (!emotionTag.isBlank()) userMsgMap.put("emotion", emotionTag);
        convMemory.append(userId, userMsgMap);
        convMemory.append(userId, Map.of("role", "assistant", "content", reply));

        String sid = tool.ToolUserContext.getSessionId();
        java.time.Instant occurredAt = java.time.Instant.now();
        String time = java.time.LocalDateTime.ofInstant(occurredAt, java.time.ZoneId.systemDefault()).toString();
        Map<String, Object> userMsg = includeEmotionInSessionMessage
            ? new LinkedHashMap<>(userMsgMap) : new LinkedHashMap<>();
        userMsg.put("id", persistentMessageId("user"));
        userMsg.put("sender", "user");
        userMsg.put("text", userMessage);
        userMsg.put("time", time);
        userMsg.put("sessionId", sid);
        sessionService.appendMessage(userId, sid, userMsg);

        Map<String, Object> agentMsg = new LinkedHashMap<>();
        agentMsg.put("id", persistentMessageId("agent"));
        agentMsg.put("sender", "agent");
        agentMsg.put("text", reply);
        agentMsg.put("time", time);
        agentMsg.put("sessionId", sid);
        sessionService.appendMessage(userId, sid, agentMsg);
        onCompletedTurn(userId, sid, userMessage, reply, emotion, occurredAt);
    }

    // ==================== Text Chat ====================

    public model.ChatResult chat(String userId, String userMessage) {
        return chat(userId, userMessage, 6, Set.of());
    }

    /** 桌面端可传入 contextRounds 控制加载的对话轮数 */
    public model.ChatResult chat(String userId, String userMessage, int contextRounds) {
        return chat(userId, userMessage, contextRounds, Set.of());
    }
    public model.ChatResult chat(String userId, String userMessage, int contextRounds, Set<String> activeSkills) {
        if (!isConfigured()) {
            return model.ChatResult.of("请先配置 LLM API。", false);
        }
        tool.ToolUserContext.set(userId);
        try {
            // Step 1: 意图路由（技能由前端选择，不再经过 LLM 路由）
            logger.log("INFO", "[1/2] " + routingStageDescription() + "...");
            PreCallResult pre = preCall(userId, userMessage);
            EmotionService.EmotionResult emotion = emotionService.enrich(userMessage, pre.emotion());
            emotionService.saveToHistory(userId, emotion);
            String trend = emotionService.getEmotionTrend(userId, emotion);

            ToolCallback[] callbacks = getToolCallbacks(pre.groups());
            logger.log("INFO", "[1/2] 路由 → " + (pre.groups().isEmpty() ? "NONE" : pre.groups())
                + (callbacks.length > 0 ? " (" + callbacks.length + "工具)" : "")
                + " | 情感 → " + emotion.emotion() + "(" + String.format("%.1f", emotion.intensity()) + ")"
                + (trend.isBlank() ? "" : " | " + trend)
                + (activeSkills.isEmpty() ? "" : " | 技能 → " + String.join(", ", activeSkills)));

            // Step 2: LLM call
            ToolCallLimitAdvisor.reset();
            logger.log("INFO", "[2/2] 调用 LLM...");
            String systemPrompt = buildSystemPrompt(userId, userMessage, emotion, trend, activeSkills);

            // 有工具可用且意图路由命中时，强制注入工具调用指令到 prompt 最前面
            if (callbacks.length > 0 && !pre.groups().isEmpty()) {
                String toolNames = java.util.Arrays.stream(callbacks)
                    .map(tc -> tc.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.joining("、"));
                systemPrompt = "【系统指令】用户消息涉及 " + String.join("、", pre.groups())
                    + " 功能。你必须调用工具获取真实数据，严禁凭空编造。可用工具：" + toolNames
                    + "\n\n" + systemPrompt;
            }

            // 桌面端读全部历史，微信端保持固定轮数
            int historyLimit = isWechatSession() ? contextRounds : 200;
            var chatSpec = buildChatClient()
                .prompt()
                .system(systemPrompt)
                .messages(loadHistory(userId, historyLimit))
                .user(userMessage)
                .toolCallbacks(callbacks);
            chatSpec = applyChatOptions(chatSpec);
            // 提取主 LLM 调用的 token 用量
            int mainPrompt = 0, mainCompletion = 0;
            String reply;
            try {
                var mainResp = chatSpec.call().chatResponse();
                reply = mainResp.getResult().getOutput().getText();
                if (mainResp.getMetadata() != null && mainResp.getMetadata().getUsage() != null) {
                    var usage = mainResp.getMetadata().getUsage();
                    mainPrompt = (int) usage.getPromptTokens();
                    mainCompletion = (int) usage.getCompletionTokens();
                }
            } catch (Exception tokenEx) {
                // 如果获取 token 失败，回退到简单调用
                reply = chatSpec.call().content();
            }

            // Step 3: post-processing
            logger.log("INFO", "[2/2] 后处理...");

            // 危险信号提醒
            if (emotion.alert() != null) reply = emotion.alert() + "\n\n" + (reply != null ? reply : "");

            if (reply == null || reply.isEmpty()) {
                reply = "MindPet 暂时有点累，稍后再试试吧~";
            }
            reply += tool.ToolUserContext.missingGeneratedFilesMarkdown(reply);

            // 写入 Redis 短期记忆（带情感标签）
            String emotionTag = emotion.toTag();
            Map<String, Object> userMsgMap = new LinkedHashMap<>();
            userMsgMap.put("role", "user");
            userMsgMap.put("content", userMessage);
            if (!emotionTag.isBlank()) userMsgMap.put("emotion", emotionTag);
            convMemory.append(userId, userMsgMap);
            convMemory.append(userId, Map.of("role", "assistant", "content", reply));
            // 持久化消息到 Redis，格式兼容前端（sender + text）
            String sid = tool.ToolUserContext.getSessionId();
            java.time.Instant occurredAt = java.time.Instant.now();
            String time = java.time.LocalDateTime.ofInstant(occurredAt, java.time.ZoneId.systemDefault()).toString();
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("id", persistentMessageId("user"));
            userMsg.put("sender", "user"); userMsg.put("text", userMessage);
            userMsg.put("time", time); userMsg.put("sessionId", sid);
            sessionService.appendMessage(userId, sid, userMsg);
            Map<String, Object> agentMsg = new LinkedHashMap<>();
            agentMsg.put("id", persistentMessageId("agent"));
            agentMsg.put("sender", "agent"); agentMsg.put("text", reply);
            agentMsg.put("time", time); agentMsg.put("sessionId", sid);
            sessionService.appendMessage(userId, sid, agentMsg);

            // 完整回合跨端、跨会话计数并触发记忆馆长
            onCompletedTurn(userId, sid, userMessage, reply, emotion, occurredAt);

            boolean toolsUsed = tool.ToolUserContext.isToolsUsed();
            // 合并 preCall + 主调用的 token 数量
            int totalPrompt = pre.promptTokens() + mainPrompt;
            int totalCompletion = pre.completionTokens() + mainCompletion;
            return model.ChatResult.of(reply, toolsUsed, totalPrompt, totalCompletion);
        } catch (Exception e) {
            logger.log("ERROR", "AI 调用失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return model.ChatResult.of("MindPet 暂时有点累，稍后再试试吧~", false);
        } finally {
            tool.ToolUserContext.clear();
        }
    }

    /**
     * Desktop true-streaming chat. The synchronous chat methods remain unchanged for other callers.
     */
    public model.ChatResult chatStream(String userId, String userMessage, int contextRounds,
                                       Consumer<String> onDelta) {
        return chatStream(userId, userMessage, contextRounds, onDelta, Set.of());
    }
    public model.ChatResult chatStream(String userId, String userMessage, int contextRounds,
                                       Consumer<String> onDelta, Set<String> activeSkills) {
        String fallback = "MindPet 暂时有点累，稍后再试试吧~";
        if (!isConfigured()) {
            String message = "请先配置 LLM API。";
            onDelta.accept(message);
            return model.ChatResult.of(message, false);
        }

        tool.ToolUserContext.set(userId);
        AtomicBoolean emitted = new AtomicBoolean();
        AtomicBoolean streamedToolsUsed = new AtomicBoolean();
        Consumer<String> emit = delta -> {
            emitted.set(true);
            onDelta.accept(delta);
        };
        try {
            logger.log("INFO", "[1/2] " + routingStageDescription() + "...");
            PreCallResult pre = preCall(userId, userMessage);
            EmotionService.EmotionResult emotion = emotionService.enrich(userMessage, pre.emotion());
            emotionService.saveToHistory(userId, emotion);
            String trend = emotionService.getEmotionTrend(userId, emotion);

            ToolCallback[] callbacks = getToolCallbacks(pre.groups(), streamedToolsUsed);
            logger.log("INFO", "[1/2] 路由 → " + (pre.groups().isEmpty() ? "NONE" : pre.groups())
                + (callbacks.length > 0 ? " (" + callbacks.length + "工具)" : "")
                + " | 情感 → " + emotion.emotion() + "(" + String.format("%.1f", emotion.intensity()) + ")"
                + (trend.isBlank() ? "" : " | " + trend)
                + (activeSkills.isEmpty() ? "" : " | 技能 → " + String.join(", ", activeSkills)));

            ToolCallLimitAdvisor.reset();
            logger.log("INFO", "[2/2] 流式调用 LLM...");
            String systemPrompt = buildSystemPrompt(userId, userMessage, emotion, trend, activeSkills);
            if (callbacks.length > 0 && !pre.groups().isEmpty()) {
                String toolNames = Arrays.stream(callbacks)
                    .map(tc -> tc.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.joining("、"));
                systemPrompt = "【系统指令】用户消息涉及 " + String.join("、", pre.groups())
                    + " 功能。你必须调用工具获取真实数据，严禁凭空编造。可用工具：" + toolNames
                    + "\n\n" + systemPrompt;
            }

            int historyLimit = isWechatSession() ? contextRounds : 200;
            ChatClient.ChatClientRequestSpec chatSpec = buildChatClient()
                .prompt()
                .system(systemPrompt)
                .messages(loadHistory(userId, historyLimit))
                .user(userMessage)
                .toolCallbacks(callbacks);
            chatSpec = applyChatOptions(chatSpec);

            String alertPrefix = emotion.alert() == null ? "" : emotion.alert() + "\n\n";
            if (!alertPrefix.isEmpty()) emit.accept(alertPrefix);
            StreamedResponse streamed = streamResponse(chatSpec, emit);
            String reply = alertPrefix + streamed.text();
            if (reply.isEmpty()) {
                reply = fallback;
                emit.accept(reply);
            }
            String generatedFilesMarkdown = tool.ToolUserContext.missingGeneratedFilesMarkdown(reply);
            if (!generatedFilesMarkdown.isBlank()) {
                reply += generatedFilesMarkdown;
                emit.accept(generatedFilesMarkdown);
            }

            persistStreamedConversation(userId, userMessage, reply, emotion, false);
            boolean hasMainUsage = streamed.promptTokens() > 0 || streamed.completionTokens() > 0;
            int totalPrompt = hasMainUsage ? pre.promptTokens() + streamed.promptTokens() : 0;
            int totalCompletion = hasMainUsage ? pre.completionTokens() + streamed.completionTokens() : 0;
            return model.ChatResult.of(reply,
                streamedToolsUsed.get() || tool.ToolUserContext.isToolsUsed(),
                totalPrompt, totalCompletion);
        } catch (Exception e) {
            logger.log("ERROR", "AI 流式调用失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (emitted.get()) {
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException(e);
            }
            emit.accept(fallback);
            return model.ChatResult.of(fallback, streamedToolsUsed.get());
        } finally {
            tool.ToolUserContext.clear();
        }
    }

    // ==================== Image Chat ====================

    public model.ChatResult chatWithImage(String userId, String userMessage, byte[] imageBytes, String fileName) {
        return chatWithImage(userId, userMessage, imageBytes, fileName, 6, Set.of());
    }
    public model.ChatResult chatWithImage(String userId, String userMessage, byte[] imageBytes, String fileName, int contextRounds) {
        return chatWithImage(userId, userMessage, imageBytes, fileName, contextRounds, Set.of());
    }
    public model.ChatResult chatWithImage(String userId, String userMessage, byte[] imageBytes, String fileName, int contextRounds, Set<String> activeSkills) {
        if (!isConfigured()) {
            return model.ChatResult.of("请先配置 LLM API。", false);
        }
        tool.ToolUserContext.set(userId);
        tool.ToolUserContext.setImageData(imageBytes);  // 存储图片数据，供发票OCR等工具使用
        try {
            // Intent routing (skills from frontend, no LLM routing)
            String textPrompt = (userMessage == null || userMessage.isBlank())
                ? "你看到了什么？像朋友一样聊聊~"
                : userMessage;
            PreCallResult pre = preCall(userId, textPrompt);
            EmotionService.EmotionResult emotion = emotionService.enrich(textPrompt, pre.emotion());
            emotionService.saveToHistory(userId, emotion);
            String trend = emotionService.getEmotionTrend(userId, emotion);
            ToolCallback[] callbacks = getToolCallbacks(pre.groups());

            String systemPrompt = buildSystemPrompt(userId, textPrompt, emotion, trend, activeSkills);

            // 有工具可用且意图路由命中时，强制注入工具调用指令到 prompt 最前面
            if (callbacks.length > 0 && !pre.groups().isEmpty()) {
                String toolNames = java.util.Arrays.stream(callbacks)
                    .map(tc -> tc.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.joining("、"));
                systemPrompt = "【系统指令】用户消息涉及 " + String.join("、", pre.groups())
                    + " 功能。你必须调用工具获取真实数据，严禁凭空编造。可用工具：" + toolNames
                    + "\n\n" + systemPrompt;
            }

            // Determine mime type (must be effectively final for lambda)
            final org.springframework.util.MimeType mimeType;
            if (fileName != null) {
                String lower = fileName.toLowerCase();
                if (lower.endsWith(".png")) mimeType = MimeTypeUtils.IMAGE_PNG;
                else if (lower.endsWith(".gif")) mimeType = MimeTypeUtils.IMAGE_GIF;
                else if (lower.endsWith(".webp")) mimeType = new org.springframework.util.MimeType("image", "webp");
                else mimeType = MimeTypeUtils.IMAGE_JPEG;
            } else {
                mimeType = MimeTypeUtils.IMAGE_JPEG;
            }

            var imgChatSpec = buildChatClient()
                .prompt()
                .system(systemPrompt)
                .messages(loadHistory(userId, isWechatSession() ? contextRounds : 200))
                .user(u -> u.text(textPrompt).media(
                    mimeType,
                    new ByteArrayResource(imageBytes) {
                        @Override
                        public String getFilename() { return fileName != null ? fileName : "image.jpg"; }
                    }
                ))
                .toolCallbacks(callbacks);
            imgChatSpec = applyChatOptions(imgChatSpec);
            // 提取图片对话的 token 用量
            int imgPrompt = 0, imgCompletion = 0;
            String reply;
            try {
                var imgResp = imgChatSpec.call().chatResponse();
                reply = imgResp.getResult().getOutput().getText();
                if (imgResp.getMetadata() != null && imgResp.getMetadata().getUsage() != null) {
                    var usage = imgResp.getMetadata().getUsage();
                    imgPrompt = (int) usage.getPromptTokens();
                    imgCompletion = (int) usage.getCompletionTokens();
                }
            } catch (Exception tokenEx) {
                reply = imgChatSpec.call().content();
            }

            if (reply == null || reply.isEmpty()) {
                reply = "MindPet 暂时有点累，稍后再试试吧~";
            }
            reply += tool.ToolUserContext.missingGeneratedFilesMarkdown(reply);

            // 写入 Redis 短期记忆（带情感标签）
            String emotionTag = emotion.toTag();
            Map<String, Object> userMsgMap = new LinkedHashMap<>();
            userMsgMap.put("role", "user");
            userMsgMap.put("content", textPrompt);
            if (!emotionTag.isBlank()) userMsgMap.put("emotion", emotionTag);
            convMemory.append(userId, userMsgMap);
            convMemory.append(userId, Map.of("role", "assistant", "content", reply));
            String sid2 = tool.ToolUserContext.getSessionId();
            java.time.Instant occurredAt = java.time.Instant.now();
            String time2 = java.time.LocalDateTime.ofInstant(occurredAt, java.time.ZoneId.systemDefault()).toString();
            Map<String, Object> um = new LinkedHashMap<>(userMsgMap);
            um.put("id", persistentMessageId("user"));
            um.put("sender", "user"); um.put("text", textPrompt);
            um.put("time", time2); um.put("sessionId", sid2);
            sessionService.appendMessage(userId, sid2, um);
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", persistentMessageId("agent"));
            am.put("sender", "agent"); am.put("text", reply);
            am.put("time", time2); am.put("sessionId", sid2);
            sessionService.appendMessage(userId, sid2, am);

            onCompletedTurn(userId, sid2, textPrompt, reply, emotion, occurredAt);

            boolean toolsUsed = tool.ToolUserContext.isToolsUsed();
            int totalPrompt = pre.promptTokens() + imgPrompt;
            int totalCompletion = pre.completionTokens() + imgCompletion;
            return model.ChatResult.of(reply, toolsUsed, totalPrompt, totalCompletion);
        } catch (Exception e) {
            logger.log("ERROR", "图片识别失败: " + e.getMessage());
            return model.ChatResult.of("图片识别失败，请稍后再试试吧~", false);
        } finally {
            tool.ToolUserContext.clear();
        }
    }

    /** True-streaming image chat for the desktop endpoint. */
    public model.ChatResult chatWithImageStream(String userId, String userMessage, byte[] imageBytes,
                                                String fileName, int contextRounds,
                                                Consumer<String> onDelta) {
        return chatWithImageStream(userId, userMessage, imageBytes, fileName, contextRounds, onDelta, Set.of());
    }
    public model.ChatResult chatWithImageStream(String userId, String userMessage, byte[] imageBytes,
                                                String fileName, int contextRounds,
                                                Consumer<String> onDelta, Set<String> activeSkills) {
        String fallback = "图片识别失败，请稍后再试试吧~";
        if (!isConfigured()) {
            String message = "请先配置 LLM API。";
            onDelta.accept(message);
            return model.ChatResult.of(message, false);
        }

        tool.ToolUserContext.set(userId);
        tool.ToolUserContext.setImageData(imageBytes);
        AtomicBoolean emitted = new AtomicBoolean();
        AtomicBoolean streamedToolsUsed = new AtomicBoolean();
        Consumer<String> emit = delta -> {
            emitted.set(true);
            onDelta.accept(delta);
        };
        try {
            String textPrompt = (userMessage == null || userMessage.isBlank())
                ? "你看到了什么？像朋友一样聊聊~"
                : userMessage;
            PreCallResult pre = preCall(userId, textPrompt);
            EmotionService.EmotionResult emotion = emotionService.enrich(textPrompt, pre.emotion());
            emotionService.saveToHistory(userId, emotion);
            String trend = emotionService.getEmotionTrend(userId, emotion);
            ToolCallback[] callbacks = getToolCallbacks(pre.groups(), streamedToolsUsed);

            String systemPrompt = buildSystemPrompt(userId, textPrompt, emotion, trend, activeSkills);
            if (callbacks.length > 0 && !pre.groups().isEmpty()) {
                String toolNames = Arrays.stream(callbacks)
                    .map(tc -> tc.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.joining("、"));
                systemPrompt = "【系统指令】用户消息涉及 " + String.join("、", pre.groups())
                    + " 功能。你必须调用工具获取真实数据，严禁凭空编造。可用工具：" + toolNames
                    + "\n\n" + systemPrompt;
            }

            final org.springframework.util.MimeType mimeType;
            if (fileName != null) {
                String lower = fileName.toLowerCase();
                if (lower.endsWith(".png")) mimeType = MimeTypeUtils.IMAGE_PNG;
                else if (lower.endsWith(".gif")) mimeType = MimeTypeUtils.IMAGE_GIF;
                else if (lower.endsWith(".webp")) mimeType = new org.springframework.util.MimeType("image", "webp");
                else mimeType = MimeTypeUtils.IMAGE_JPEG;
            } else {
                mimeType = MimeTypeUtils.IMAGE_JPEG;
            }

            ToolCallLimitAdvisor.reset();
            ChatClient.ChatClientRequestSpec chatSpec = buildChatClient()
                .prompt()
                .system(systemPrompt)
                .messages(loadHistory(userId, isWechatSession() ? contextRounds : 200))
                .user(u -> u.text(textPrompt).media(
                    mimeType,
                    new ByteArrayResource(imageBytes) {
                        @Override
                        public String getFilename() { return fileName != null ? fileName : "image.jpg"; }
                    }
                ))
                .toolCallbacks(callbacks);
            chatSpec = applyChatOptions(chatSpec);

            StreamedResponse streamed = streamResponse(chatSpec, emit);
            String reply = streamed.text();
            if (reply.isEmpty()) {
                reply = "MindPet 暂时有点累，稍后再试试吧~";
                emit.accept(reply);
            }
            String generatedFilesMarkdown = tool.ToolUserContext.missingGeneratedFilesMarkdown(reply);
            if (!generatedFilesMarkdown.isBlank()) {
                reply += generatedFilesMarkdown;
                emit.accept(generatedFilesMarkdown);
            }

            persistStreamedConversation(userId, textPrompt, reply, emotion, true);
            boolean hasMainUsage = streamed.promptTokens() > 0 || streamed.completionTokens() > 0;
            int totalPrompt = hasMainUsage ? pre.promptTokens() + streamed.promptTokens() : 0;
            int totalCompletion = hasMainUsage ? pre.completionTokens() + streamed.completionTokens() : 0;
            return model.ChatResult.of(reply,
                streamedToolsUsed.get() || tool.ToolUserContext.isToolsUsed(),
                totalPrompt, totalCompletion);
        } catch (Exception e) {
            logger.log("ERROR", "图片流式识别失败: " + e.getMessage());
            if (emitted.get()) {
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException(e);
            }
            emit.accept(fallback);
            return model.ChatResult.of(fallback, streamedToolsUsed.get());
        } finally {
            tool.ToolUserContext.clear();
        }
    }

    // ==================== Tool Catalog ====================

    /**
     * 返回所有可用工具的清单，供 skill 生成 LLM 参考。
     * 每条包含 name、description、group、parameters、source。
     */
    public List<Map<String, Object>> getToolCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        Map<String, ToolCallbackProvider> providers = appCtx.getBeansOfType(ToolCallbackProvider.class);
        for (var entry : providers.entrySet()) {
            try {
                for (ToolCallback tc : entry.getValue().getToolCallbacks()) {
                    var def = tc.getToolDefinition();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", def.name());
                    item.put("description", def.description() != null ? def.description() : "");

                    // 提取参数名列表
                    List<String> paramNames = new ArrayList<>();
                    try {
                        String inputSchema = def.inputSchema();
                        if (inputSchema != null && !inputSchema.isBlank()) {
                            var schemaNode = new ObjectMapper().readTree(inputSchema);
                            var props = schemaNode.get("properties");
                            if (props != null) {
                                var fieldNames = props.fieldNames();
                                while (fieldNames.hasNext()) paramNames.add(fieldNames.next());
                            }
                        }
                    } catch (Exception ignored) {}
                    item.put("parameters", paramNames);

                    // 反向查找 group
                    String toolName = def.name();
                    String group = findGroupForTool(toolName);
                    item.put("group", group != null ? group : "其他");

                    // source 标记
                    if (toolName.contains("__")) {
                        item.put("source", toolName.contains("desktop") ? "desktop" : "mcp");
                    } else {
                        item.put("source", "java");
                    }

                    catalog.add(item);
                }
            } catch (Exception e) {
                logger.log("WARN", "  getToolCatalog: provider[" + entry.getKey() + "] failed: " + e.getMessage());
            }
        }
        return catalog;
    }

    private String findGroupForTool(String toolName) {
        if (toolName.contains("__")) {
            String[] parts = toolName.split("__");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("desktop".equals(parts[i])) {
                    String category = parts[i + 1];
                    for (var entry : TOOL_GROUPS.entrySet()) {
                        if (entry.getValue().contains(category)) return entry.getKey();
                    }
                    return category;
                }
            }
            return "MCP";
        }
        for (var entry : TOOL_GROUPS.entrySet()) {
            for (String expected : entry.getValue()) {
                if (matchesToolName(toolName, expected)) return entry.getKey();
            }
        }
        return null;
    }

    // ==================== Skill Generation ====================

    private static final String SKILL_GEN_SYSTEM_PROMPT =
        "你是一个 AI 技能编排专家。你的任务是根据用户的需求描述，结合可用的工具清单，" +
        "生成一个结构化的技能定义文件（SKILL.md）。\n\n" +
        "## 规则\n" +
        "1. 只能使用「可用工具清单」中列出的工具，严禁编造不存在的工具名\n" +
        "2. 每个 section 都必须填写，不能省略\n" +
        "3. 「可用工具」section 只列出该技能实际会用到的工具\n" +
        "4. 「示例对话」至少给出一组示例\n" +
        "5. 技能名称使用中文，简洁明确\n\n" +
        "## 输出格式（严格按此 Markdown 结构）\n\n" +
        "# {技能名称}\n\n" +
        "## 触发条件\n" +
        "- 关键词: ...\n" +
        "- 场景: ...\n\n" +
        "## 行为指令\n" +
        "1. 步骤一：...\n" +
        "2. 步骤二：...\n\n" +
        "## 可用工具\n" +
        "- `toolName` — 在此技能中的用途\n\n" +
        "## 输出格式\n" +
        "- 回复风格：...\n" +
        "- 内容结构：...\n\n" +
        "## 约束\n" +
        "- 不要...\n" +
        "- 必须...\n\n" +
        "## 示例对话\n" +
        "**用户**: \"...\"\n" +
        "**AI**: \"...\"\n\n" +
        "直接输出 SKILL.md 内容，不要包裹在代码块中，不要额外解释。";

    /**
     * 根据用户自然语言描述生成 SKILL.md 内容。
     */
    public Map<String, Object> generateSkill(String userDescription, String skillName) {
        try {
            List<Map<String, Object>> catalog = getToolCatalog();
            StringBuilder toolsSection = new StringBuilder();
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (var t : catalog) {
                String group = String.valueOf(t.getOrDefault("group", "其他"));
                grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(t);
            }
            for (var entry : grouped.entrySet()) {
                toolsSection.append("### ").append(entry.getKey()).append("\n");
                for (var t : entry.getValue()) {
                    toolsSection.append("- **").append(t.get("name")).append("**");
                    String desc = String.valueOf(t.getOrDefault("description", ""));
                    if (!desc.isBlank()) toolsSection.append("：").append(desc);
                    @SuppressWarnings("unchecked")
                    List<String> params = (List<String>) t.get("parameters");
                    if (params != null && !params.isEmpty()) {
                        toolsSection.append("（参数: ").append(String.join(", ", params)).append("）");
                    }
                    toolsSection.append("\n");
                }
                toolsSection.append("\n");
            }

            String userPrompt = "## 可用工具清单\n\n" + toolsSection + "\n## 用户需求\n\n" +
                (skillName != null && !skillName.isBlank() ? "技能名称：" + skillName + "\n" : "") +
                userDescription;

            var spec = buildChatClient()
                .prompt()
                .system(SKILL_GEN_SYSTEM_PROMPT)
                .user(userPrompt);
            if (dynamicConfig.hasOverride() && !dynamicConfig.getModel().isBlank()) {
                spec = spec.options(OpenAiChatOptions.builder().model(dynamicConfig.getModel()).build());
            }
            String content = spec.call().content();
            if (content == null || content.isBlank()) {
                return Map.of("status", "error", "message", "LLM 返回空内容");
            }

            // 尝试从内容中提取技能名称
            String extractedName = skillName != null && !skillName.isBlank() ? skillName : "";
            if (extractedName.isBlank()) {
                String firstLine = content.strip().split("\\R")[0];
                extractedName = firstLine.replaceAll("^#\\s*", "").strip();
            }

            logger.log("INFO", "[SkillGen] 已生成技能: " + extractedName + " (" + content.length() + " 字符)");
            return Map.of("status", "ok", "content", content, "name", extractedName,
                "toolCount", catalog.size());
        } catch (Exception e) {
            logger.log("ERROR", "[SkillGen] 生成失败: " + e.getMessage());
            return Map.of("status", "error", "message", "生成失败: " + e.getMessage());
        }
    }

    // ==================== Simple Chat (no tools, no memory) ====================

    public String chatSimple(String prompt) {
        try {
            var spec = buildChatClient()
                .prompt()
                .system(SYSTEM_PROMPT + "\n" + IDENTITY_PROMPT)
                .user(prompt);
            if (dynamicConfig.hasOverride() && !dynamicConfig.getModel().isBlank()) {
                spec = spec.options(OpenAiChatOptions.builder().model(dynamicConfig.getModel()).build());
            }
            return spec.call().content();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== System Prompt ====================

    private String buildSystemPrompt(String userId, String query) { return buildSystemPrompt(userId, query, null, "", Set.of()); }
    private String buildSystemPrompt(String userId, String query, EmotionService.EmotionResult emotion, String trend) {
        return buildSystemPrompt(userId, query, emotion, trend, Set.of());
    }
    private String buildSystemPrompt(String userId, String query, EmotionService.EmotionResult emotion, String trend, Set<String> skills) {
        // 兼容前端历史配置，但不把旧品牌名继续传给模型。
        String basePrompt = dynamicConfig.hasSystemPrompt()
            ? normalizeIdentity(dynamicConfig.getSystemPrompt())
            : SYSTEM_PROMPT;
        basePrompt += "\n" + IDENTITY_PROMPT;
        String prompt = "现在是 " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss")) + "（星期" + "一二三四五六日".charAt(java.time.LocalDate.now().getDayOfWeek().getValue() - 1) + "）。\n" + basePrompt;

        // 工作记忆（跨对话状态便签）
        String wm = memoryCurator.getWorkingMemoryPrompt(userId);
        if (!wm.isBlank()) prompt += "\n\n" + wm;

        // 情感上下文（含语境翻转纠正）
        if (emotion != null) {
            String ec = emotion.toPromptContext();
            if (!ec.isBlank()) prompt += "\n\n" + ec;
        }

        // 情感变化趋势（显式对比，Giftia 方案）
        if (trend != null && !trend.isBlank()) {
            prompt += "\n" + trend;
        }

        // User profile
        if (userId != null) {
            try {
                String profile = profileService.getProfileContext(userId);
                if (profile != null && !profile.isBlank()) {
                    prompt += "\n\n" + profile;
                }
            } catch (Exception ignored) {}
        }
        // RAG lookups — compute embedding ONCE, reuse for all 3 lookups
        if (userId != null && query != null) {
            float[] vec = embedService.embed(query);
            if (vec != null) {
                try {
                    String insights = insightService.getInsightContext(userId, vec);
                    if (insights != null && !insights.isBlank()) {
                        prompt += "\n\n" + insights + "\n请自然地体现这些认知，但不要刻意复述。";
                    }
                } catch (Exception ignored) {}
                try {
                    String growth = insightService.getGrowthContext(userId, vec);
                    if (growth != null && !growth.isBlank()) {
                        prompt += "\n\n" + growth;
                    }
                } catch (Exception ignored) {}
                try {
                    var memories = pgMemory.search(userId, query, vec, 3);
                    if (!memories.isEmpty()) {
                        StringBuilder sb = new StringBuilder("\n\n## 相关历史记忆\n");
                        for (var m : memories) {
                            sb.append("- ").append(m.toPromptLine()).append("\n");
                        }
                        sb.append("请自然地参考这些记忆，不要刻意复述原文。");
                        prompt += sb.toString();
                    }
                } catch (Exception ignored) {}
                try {
                    String graphContext = knowledgeGraph.getRagContext(userId, query, vec, 4);
                    if (!graphContext.isBlank()) prompt += "\n\n" + graphContext;
                } catch (Exception ignored) {}
            }
        }
        // 前端选择的活跃技能 — 只注入名称列表，不注入 full content（前端已选定，无需 LLM 路由）
        if (skills != null && !skills.isEmpty()) {
            prompt += "\n\n## 用户启用的技能\n";
            for (String name : skills) {
                prompt += "- " + name + "\n";
            }
            logger.log("INFO", "[Skill] 已注入 " + skills.size() + " 个技能: " + String.join(", ", skills));
        }

        return prompt;
    }

    private String normalizeIdentity(String prompt) {
        return prompt.replace("小晴", "MindPet")
            .replaceAll("(?i)xiaoqing", "MindPet");
    }

    private void onCompletedTurn(String userId, String sessionId,
                                 String userMessage, String assistantReply,
                                 EmotionService.EmotionResult emotion,
                                 java.time.Instant occurredAt) {
        memoryCurator.onCompletedTurn(userId, sessionId, userMessage, assistantReply);
        knowledgeGraph.onCompletedTurn(userId, sessionId, userMessage, assistantReply,
            emotion == null ? "neutral" : emotion.emotion(), occurredAt);
    }

    private String persistentMessageId(String sender) {
        String requestId = tool.ToolUserContext.getRequestId();
        String base = requestId == null || requestId.isBlank()
            ? String.valueOf(System.currentTimeMillis()) : requestId;
        return base + "-" + sender;
    }
}
