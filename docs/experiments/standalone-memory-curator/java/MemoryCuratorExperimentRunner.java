package experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.SqliteStorageConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import service.CuratorTurnStore;
import service.DynamicChatClientFactory;
import service.MemoryCuratorCommitService;
import service.MemoryCuratorService;
import service.MemoryContentSafety;
import service.MemoryFactService;
import service.ProfileProjectionService;
import service.UserInsightService;
import service.UserProfileService;
import util.Logger;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * External experiment runner. It bootstraps only the production memory-curator
 * services it needs and points them at a new, run-local SQLite file.
 */
public final class MemoryCuratorExperimentRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REVIEW_TURNS = 20;
    private static final int TRIGGER_TURNS = 15;

    private MemoryCuratorExperimentRunner() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path dataset = Path.of(required(options, "dataset")).toAbsolutePath().normalize();
        Path output = Path.of(required(options, "output-dir")).toAbsolutePath().normalize();
        String provider = options.getOrDefault("provider", "fixture");
        int limit = Integer.parseInt(options.getOrDefault("limit", "0"));
        if (!Set.of("fixture", "llm").contains(provider)) {
            throw new IllegalArgumentException("--provider must be fixture or llm");
        }
        if (provider.equals("llm") && limit <= 0) {
            throw new IllegalArgumentException("LLM mode requires a positive --limit to bound API usage");
        }
        if (Files.exists(output.resolve("memory-curator-experiment.sqlite"))) {
            throw new IllegalArgumentException("Output directory already contains an experiment database: " + output);
        }
        Files.createDirectories(output);
        List<JsonNode> samples = readJsonl(dataset);
        String samplingStrategy = "all_rows";
        if (limit > 0 && samples.size() > limit) {
            if (provider.equals("llm")) {
                samples = stratifiedSample(samples, limit);
                samplingStrategy = "balanced_by_case_type";
            } else {
                samples = new ArrayList<>(samples.subList(0, limit));
                samplingStrategy = "first_n_rows";
            }
        }
        if (samples.isEmpty()) throw new IllegalArgumentException("Dataset contains no samples");

        Path database = output.resolve("memory-curator-experiment.sqlite");
        Logger logger = new Logger() {
            @Override public void log(String level, String message) {
                if ("ERROR".equalsIgnoreCase(level)) super.log(level, message);
            }
        };
        // A non-empty, deliberately nonexistent extension path disables sqlite-vec loading.
        DataSource dataSource = new SqliteStorageConfig().sqliteDataSource(
            database.toString(), output.resolve("sqlite-vec-disabled").toString());
        try (AutoCloseable closeable = (AutoCloseable) dataSource) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            CuratorTurnStore turnStore = new CuratorTurnStore(jdbc, JSON, logger);
            Clock experimentClock = Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
            MemoryFactService factService = new MemoryFactService(jdbc, experimentClock);
            ProfileProjectionService profileService = new ProfileProjectionService(jdbc, experimentClock);
            UserProfileService profileWriter = new UserProfileService(jdbc, profileService);
            // Keep embedding providers out of this experiment while retaining real SQL persistence.
            UserInsightService insightService = new UserInsightService(jdbc, null, null, logger) {
                @Override public byte[] prepareEmbedding(String text) { return null; }
            };
            MemoryCuratorCommitService commitService = new MemoryCuratorCommitService(
                factService, profileService, insightService, turnStore, logger, experimentClock);

            RunStats stats = new RunStats();
            List<Map<String, Object>> failures = new ArrayList<>();
            List<Map<String, Object>> snapshots = new ArrayList<>();
            List<Map<String, Object>> proposals = new ArrayList<>();
            List<Map<String, Object>> curatorRuns = new ArrayList<>();
            List<Map<String, Object>> modelResponses = new ArrayList<>();
            AtomicReference<String> capturedResponse = new AtomicReference<>();
            MemoryCuratorService curator = provider.equals("llm")
                ? createCurator(options, jdbc, turnStore, profileWriter, insightService,
                    transactionalCommit(factService, profileService, insightService, turnStore, logger, transaction),
                    logger, capturedResponse)
                : null;

            for (JsonNode sample : samples) {
                String sampleId = text(sample, "sample_id");
                if (sampleId.isBlank()) throw new IllegalArgumentException("Every row needs sample_id");
                if (!sample.path("synthetic").asBoolean(false)) {
                    throw new IllegalArgumentException("This runner accepts synthetic rows only: " + sampleId);
                }
                String userId = "experiment-" + sampleId;
                List<JsonNode> allTurns = children(sample.path("turns"));
                if (allTurns.isEmpty()) throw new IllegalArgumentException("No turns for " + sampleId);

                long started = System.nanoTime();
                if (provider.equals("fixture")) {
                    runFixtureSample(sample, userId, allTurns, turnStore, commitService,
                        transaction, jdbc, failures, proposals, stats);
                } else {
                    capturedResponse.set(null);
                    curatorRuns.add(runLlmSample(sample, userId, allTurns, curator, turnStore, logger, stats, failures));
                    String response = capturedResponse.getAndSet(null);
                    if (response != null) modelResponses.add(Map.of(
                        "sample_id", sampleId, "raw_model_response", response));
                }
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                stats.sampleElapsedMs.add(elapsedMs);
                List<Map<String, Object>> factRows = facts(jdbc, userId);
                List<Map<String, Object>> profileRows = profiles(jdbc, userId);
                evaluate(sample, sampleId, userId, factRows, profileRows, jdbc, stats, failures);
                snapshots.add(Map.of("sample_id", sampleId, "facts", factRows, "profile", profileRows,
                    "working_memory", safeWorkingMemory(turnStore.getWorkingMemory(userId)),
                    "elapsed_ms", elapsedMs));
            }

            Map<String, Object> metrics = stats.metrics(samples.size(), provider);
            Map<String, Object> runConfig = new LinkedHashMap<>();
            runConfig.put("runner", "Java production-service experiment runner");
            runConfig.put("provider", provider);
            runConfig.put("samples", samples.size());
            runConfig.put("sampling_strategy", samplingStrategy);
            runConfig.put("selected_sample_ids", samples.stream().map(sample -> text(sample, "sample_id")).toList());
            runConfig.put("dataset", dataset.toString());
            runConfig.put("dataset_sha256", sha256(dataset));
            runConfig.put("application_database_access", false);
            runConfig.put("experiment_database", database.toString());
            runConfig.put("llm_configuration_source", provider.equals("llm")
                ? System.getenv().getOrDefault("MINDPET_EXPERIMENT_LLM_CONFIG_SOURCE", "MINDPET_EXPERIMENT_LLM_* process environment")
                : "none");
            runConfig.put("application_dynamic_llm_config_read", false);
            runConfig.put("embedding_provider", "disabled; stored insights/growth use null embeddings");
            if (provider.equals("llm")) {
                runConfig.put("model", options.getOrDefault("model", System.getenv("MINDPET_EXPERIMENT_LLM_MODEL")));
                runConfig.put("base_url", options.getOrDefault("base-url", System.getenv("MINDPET_EXPERIMENT_LLM_BASE_URL")));
            }
            writeJson(output.resolve("run-config.json"), runConfig);
            writeJson(output.resolve("metrics.json"), metrics);
            writeJsonl(output.resolve("failures.jsonl"), failures);
            writeJsonl(output.resolve("stored-memory-snapshots.jsonl"), snapshots);
            writeJsonl(output.resolve("applied-proposals.jsonl"), proposals);
            writeJsonl(output.resolve("curator-run-summaries.jsonl"), curatorRuns);
            writeJsonl(output.resolve("llm-model-responses.jsonl"), modelResponses);
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "output_dir", output.toString(), "metrics", metrics)));
        }
    }

    private static void runFixtureSample(JsonNode sample, String userId, List<JsonNode> sourceTurns,
                                        CuratorTurnStore store, MemoryCuratorCommitService commit,
                                        TransactionTemplate transaction, JdbcTemplate jdbc,
                                        List<Map<String, Object>> failures,
                                        List<Map<String, Object>> proposals, RunStats stats) throws Exception {
        appendTurns(store, userId, sourceTurns);
        Map<String, Object> fullProposal = object(sample.path("proposal"));
        boolean injected = false;
        List<CuratorTurnStore.CompletedTurn> lastBatch = List.of();
        Map<String, Object> lastProposal = Map.of();
        long lastTarget = 0;
        while (store.pendingCount(userId) > 0) {
            List<CuratorTurnStore.CompletedTurn> batch = store.recentPending(userId, REVIEW_TURNS);
            if (batch.isEmpty()) break;
            Set<String> ids = new HashSet<>();
            for (CuratorTurnStore.CompletedTurn turn : batch) ids.add(turn.turnId());
            Map<String, Object> proposal = proposalForBatch(fullProposal, ids);
            long target = store.sequenceFor(userId, batch.get(batch.size() - 1).turnId());
            if (!injected) {
                boolean rolledBack = rollbackProbe(transaction, jdbc, store, commit, userId, proposal, batch, target);
                stats.rollbackChecks++;
                stats.rollbackConsistent += rolledBack ? 1 : 0;
                if (!rolledBack) failures.add(failure(text(sample, "sample_id"), "ROLLBACK_NOT_ATOMIC", "DB rows or checkpoint survived injected rollback"));
                injected = true;
            }
            MemoryCuratorCommitService.CommitResult result = transaction.execute(status ->
                commit.commit(userId, proposal, batch, target, Map.of()));
            if (result == null) throw new IllegalStateException("Transaction returned no commit result");
            lastBatch = List.copyOf(batch);
            lastProposal = proposal;
            lastTarget = target;
            proposals.add(Map.of("sample_id", text(sample, "sample_id"), "target_sequence", target,
                "batch_turns", batch.size(), "proposal", proposal,
                "saved", result.saved(), "rejections", result.rejections()));
        }
        Map<String, Object> before = logicalSnapshot(jdbc, store, userId);
        // Replay the exact last committed batch and proposal, not a regrouped history window.
        if (!lastBatch.isEmpty()) {
            List<CuratorTurnStore.CompletedTurn> replayBatch = List.copyOf(lastBatch);
            Map<String, Object> replayProposal = lastProposal;
            long replayTarget = lastTarget;
            transaction.executeWithoutResult(status -> commit.commit(userId, replayProposal, replayBatch, replayTarget, Map.of()));
        }
        Map<String, Object> after = logicalSnapshot(jdbc, store, userId);
        stats.replayChecks++;
        stats.replayConsistent += before.equals(after) ? 1 : 0;
        if (!before.equals(after)) failures.add(failure(text(sample, "sample_id"), "REPLAY_DRIFT", "Stored logical memories changed after replay"));
    }

    private static Map<String, Object> runLlmSample(JsonNode sample, String userId, List<JsonNode> sourceTurns,
                                                    MemoryCuratorService curator, CuratorTurnStore store,
                                                    Logger logger, RunStats stats,
                                                    List<Map<String, Object>> failures) {
        int from = Math.max(0, sourceTurns.size() - TRIGGER_TURNS);
        List<JsonNode> boundedTurns = sourceTurns.subList(from, sourceTurns.size());
        long started = System.nanoTime();
        for (JsonNode turn : boundedTurns) {
            String turnId = text(turn, "turn_id");
            String zoneText = defaultText(turn, "timezone", "UTC");
            curator.onCompletedTurn(userId, turnId, text(turn, "session_id"),
                text(turn, "user"), "",
                Instant.parse(text(turn, "occurred_at")), ZoneId.of(zoneText));
        }
        if (boundedTurns.size() < TRIGGER_TURNS && store.pendingCount(userId) > 0) curator.retry(userId);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        stats.llmRuns++;
        stats.llmElapsedMs.add(elapsed);
        long checkpoint = store.checkpoint(userId);
        if (checkpoint <= 0 || store.pendingCount(userId) > 0) {
            failures.add(failure(text(sample, "sample_id"), "LLM_CURATOR_FAILED",
                sanitizedRuns(store.recentRuns(userId, 1)).toString()));
            logger.log("WARN", "LLM curator did not complete the bounded sample " + text(sample, "sample_id"));
        }
        List<Map<String, Object>> runs = sanitizedRuns(store.recentRuns(userId, 10));
        stats.curatorRunsRecorded += runs.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sample_id", text(sample, "sample_id"));
        summary.put("turns_sent_to_curator", boundedTurns.size());
        summary.put("assistant_replies_generated", 0);
        summary.put("elapsed_ms", elapsed);
        summary.put("checkpoint", checkpoint);
        summary.put("pending_turns", store.pendingCount(userId));
        summary.put("curator_runs", runs);
        return summary;
    }

    private static List<Map<String, Object>> sanitizedRuns(List<Map<String, Object>> runs) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> run : runs) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String field : List.of("target", "reviewed_turns", "saved_memories", "status",
                    "rejected_items", "rejection_reasons", "time")) {
                if (run.containsKey(field)) safe.put(field, run.get(field));
            }
            result.add(safe);
        }
        return result;
    }

    private static List<JsonNode> stratifiedSample(List<JsonNode> samples, int limit) {
        Map<String, List<JsonNode>> byType = new LinkedHashMap<>();
        for (JsonNode sample : samples) byType.computeIfAbsent(
            defaultText(sample, "case_type", "unspecified"), ignored -> new ArrayList<>()).add(sample);
        List<List<JsonNode>> groups = new ArrayList<>(byType.values());
        List<JsonNode> selected = new ArrayList<>();
        int baseQuota = limit / groups.size();
        int extra = limit % groups.size();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            List<JsonNode> group = groups.get(groupIndex);
            int quota = Math.min(group.size(), baseQuota + (groupIndex < extra ? 1 : 0));
            for (int sampleIndex = 0; sampleIndex < quota; sampleIndex++) {
                int position = Math.min(group.size() - 1,
                    ((2 * sampleIndex + 1) * group.size()) / (2 * quota));
                selected.add(group.get(position));
            }
        }
        return selected;
    }

    private static MemoryCuratorService createCurator(Map<String, String> options, JdbcTemplate jdbc,
                                                      CuratorTurnStore store, UserProfileService profile,
                                                      UserInsightService insights,
                                                      MemoryCuratorCommitService commit, Logger logger,
                                                      AtomicReference<String> capturedResponse) {
        String apiKey = envOrOption(options, "api-key", "MINDPET_EXPERIMENT_LLM_API_KEY");
        String baseUrl = envOrOption(options, "base-url", "MINDPET_EXPERIMENT_LLM_BASE_URL");
        String modelName = envOrOption(options, "model", "MINDPET_EXPERIMENT_LLM_MODEL");
        if (apiKey.isBlank() || baseUrl.isBlank() || modelName.isBlank()) {
            throw new IllegalArgumentException("LLM mode requires MINDPET_EXPERIMENT_LLM_API_KEY, MINDPET_EXPERIMENT_LLM_BASE_URL, and MINDPET_EXPERIMENT_LLM_MODEL (or matching CLI options)");
        }
        String normalizedBase = baseUrl.trim();
        if (normalizedBase.endsWith("/chat/completions")) normalizedBase = normalizedBase.substring(0, normalizedBase.length() - "/chat/completions".length());
        String finalBase = normalizedBase;
        OpenAiApi api = OpenAiApi.builder().baseUrl(finalBase).apiKey(apiKey.trim())
            .completionsPath("/chat/completions").build();
        OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model(modelName.trim()).temperature(0.0).build()).build();
        ChatClient client = recordingChatClient(ChatClient.builder(model).build(), capturedResponse);
        DynamicChatClientFactory isolatedFactory = new DynamicChatClientFactory(null, null, null, logger) {
            @Override public ChatClient build() { return client; }
            @Override public ChatClient.ChatClientRequestSpec applyCurrentModel(ChatClient.ChatClientRequestSpec spec) { return spec; }
        };
        Executor synchronous = Runnable::run;
        return new MemoryCuratorService(isolatedFactory, store, profile, insights, null,
            commit, synchronous, JSON, logger);
    }

    private static ChatClient recordingChatClient(ChatClient delegate,
                                                   AtomicReference<String> capturedResponse) {
        return (ChatClient) Proxy.newProxyInstance(ChatClient.class.getClassLoader(),
            new Class<?>[] { ChatClient.class }, (proxy, method, args) -> {
                Object result = invoke(delegate, method, args);
                return result instanceof ChatClient.ChatClientRequestSpec request
                    ? recordingRequestSpec(request, capturedResponse) : result;
            });
    }

    private static ChatClient.ChatClientRequestSpec recordingRequestSpec(
            ChatClient.ChatClientRequestSpec delegate, AtomicReference<String> capturedResponse) {
        return (ChatClient.ChatClientRequestSpec) Proxy.newProxyInstance(
            ChatClient.ChatClientRequestSpec.class.getClassLoader(),
            new Class<?>[] { ChatClient.ChatClientRequestSpec.class }, (proxy, method, args) -> {
                Object result = invoke(delegate, method, args);
                if (result instanceof ChatClient.ChatClientRequestSpec request) {
                    return recordingRequestSpec(request, capturedResponse);
                }
                if (result instanceof ChatClient.CallResponseSpec response) {
                    return recordingResponseSpec(response, capturedResponse);
                }
                return result;
            });
    }

    private static ChatClient.CallResponseSpec recordingResponseSpec(
            ChatClient.CallResponseSpec delegate, AtomicReference<String> capturedResponse) {
        return (ChatClient.CallResponseSpec) Proxy.newProxyInstance(
            ChatClient.CallResponseSpec.class.getClassLoader(),
            new Class<?>[] { ChatClient.CallResponseSpec.class }, (proxy, method, args) -> {
                Object result = invoke(delegate, method, args);
                if ("content".equals(method.getName()) && result instanceof String content) {
                    capturedResponse.set(content);
                }
                return result;
            });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static MemoryCuratorCommitService transactionalCommit(
            MemoryFactService facts, ProfileProjectionService profiles,
            UserInsightService insights, CuratorTurnStore turns, Logger logger,
            TransactionTemplate transaction) {
        return new MemoryCuratorCommitService(facts, profiles, insights, turns, logger) {
            @Override public CommitResult commit(String userId, Map<String, Object> proposal,
                    List<CuratorTurnStore.CompletedTurn> batch, long targetSequence,
                    Map<String, byte[]> embeddings) {
                CommitResult result = transaction.execute(status ->
                    super.commit(userId, proposal, batch, targetSequence, embeddings));
                if (result == null) throw new IllegalStateException("Commit transaction returned no result");
                return result;
            }
        };
    }

    private static boolean rollbackProbe(TransactionTemplate transaction, JdbcTemplate jdbc,
                                        CuratorTurnStore store, MemoryCuratorCommitService commit,
                                        String userId, Map<String, Object> proposal,
                                        List<CuratorTurnStore.CompletedTurn> batch, long target) {
        long pendingBefore = store.pendingCount(userId);
        try {
            transaction.executeWithoutResult(status -> {
                commit.commit(userId, proposal, batch, target, Map.of());
                throw new InjectedRollback();
            });
        } catch (InjectedRollback expected) {
            // Expected: the experiment deliberately aborts the enclosing DB transaction.
        }
        String workingMemory = store.getWorkingMemory(userId);
        return queryCount(jdbc, "SELECT COUNT(*) FROM memory_fact WHERE user_id=?", userId) == 0
            && queryCount(jdbc, "SELECT COUNT(*) FROM user_profile_current WHERE user_id=?", userId) == 0
            && queryCount(jdbc, "SELECT COUNT(*) FROM user_insight WHERE user_id=?", userId) == 0
            && queryCount(jdbc, "SELECT COUNT(*) FROM llm_growth WHERE user_id=?", userId) == 0
            && store.checkpoint(userId) == 0 && (workingMemory == null || workingMemory.isBlank())
            && store.pendingCount(userId) == pendingBefore;
    }

    private static Map<String, Object> proposalForBatch(Map<String, Object> proposal, Set<String> turnIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("facts", "insights", "growth")) {
            Object raw = proposal.get(field);
            List<Object> accepted = new ArrayList<>();
            if (raw instanceof List<?> items) {
                for (Object value : items) {
                    if (!(value instanceof Map<?, ?> item)) continue;
                    if (field.equals("facts")) {
                        if (turnIds.contains(String.valueOf(item.get("source_turn_id")))) accepted.add(item);
                    } else if (item.get("source_turn_ids") instanceof List<?> citations
                            && citations.stream().anyMatch(id -> turnIds.contains(String.valueOf(id)))) {
                        accepted.add(item);
                    }
                }
            }
            result.put(field, accepted);
        }
        return result;
    }

    private static void appendTurns(CuratorTurnStore store, String userId, List<JsonNode> turns) {
        for (JsonNode turn : turns) {
            String timezone = defaultText(turn, "timezone", "UTC");
            store.append(userId, text(turn, "turn_id"), text(turn, "session_id"), "synthetic",
                text(turn, "user"), text(turn, "assistant"), Instant.parse(text(turn, "occurred_at")),
                ZoneId.of(timezone));
        }
    }

    private static void evaluate(JsonNode sample, String sampleId, String userId,
                                 List<Map<String, Object>> actualFacts,
                                 List<Map<String, Object>> actualProfiles,
                                 JdbcTemplate jdbc, RunStats stats,
                                 List<Map<String, Object>> failures) {
        Set<String> expected = new LinkedHashSet<>();
        for (JsonNode item : children(sample.path("expected_facts"))) expected.add(factKey(
            text(item, "predicate"), text(item, "value"), text(item, "scope"), text(item, "assertion")));
        Set<String> actual = new LinkedHashSet<>();
        for (Map<String, Object> item : actualFacts) actual.add(factKey(
            str(item, "predicate"), str(item, "value_text"), str(item, "scope"), str(item, "assertion")));
        stats.truePositive += intersection(expected, actual);
        stats.falsePositive += difference(actual, expected);
        stats.falseNegative += difference(expected, actual);
        if (!expected.equals(actual)) failures.add(failure(sampleId, "FACT_SET_MISMATCH",
            "expected=" + expected + "; actual=" + actual));

        Map<String, String> expectedProfile = objectStringMap(sample.path("expected_profile"));
        Map<String, String> actualProfile = new LinkedHashMap<>();
        for (Map<String, Object> row : actualProfiles) actualProfile.put(str(row, "slot_key"), str(row, "value"));
        int correctProfiles = 0;
        for (Map.Entry<String, String> entry : expectedProfile.entrySet()) {
            boolean correct = canonical(entry.getValue()).equals(canonical(actualProfile.getOrDefault(entry.getKey(), "")));
            stats.profileExpected++;
            stats.profileCorrect += correct ? 1 : 0;
            correctProfiles += correct ? 1 : 0;
        }
        stats.profileWritten += actualProfile.size();
        boolean profilesEqual = expectedProfile.size() == actualProfile.size()
            && expectedProfile.entrySet().stream().allMatch(entry ->
                canonical(entry.getValue()).equals(canonical(actualProfile.getOrDefault(entry.getKey(), ""))));
        if (!profilesEqual) {
            // Keep detailed discrepancy records in a stable, human-readable shape.
            failures.add(failure(sampleId, "PROFILE_MISMATCH", "expected=" + expectedProfile + "; actual=" + actualProfile));
        }

        Set<String> validTurnIds = new HashSet<>();
        for (JsonNode turn : children(sample.path("turns"))) validTurnIds.add(text(turn, "turn_id"));
        for (Map<String, Object> item : actualFacts) {
            stats.sourceFacts++;
            stats.completeSources += validTurnIds.contains(str(item, "source_turn_id")) ? 1 : 0;
        }
        List<Map<String, Object>> activeFacts = actualFacts.stream()
            .filter(item -> "active".equals(str(item, "status"))).toList();
        Set<String> activeFactKeys = new HashSet<>();
        for (Map<String, Object> item : activeFacts) {
            activeFactKeys.add(str(item, "predicate") + "|" + canonical(str(item, "value_text"))
                + "|" + str(item, "normalized_start"));
        }
        stats.activeFacts += activeFacts.size();
        stats.supersededFacts += actualFacts.stream().filter(item -> "superseded".equals(str(item, "status"))).count();
        stats.duplicateCount += Math.max(0, activeFacts.size() - activeFactKeys.size());

        for (JsonNode expectedTime : children(sample.path("expected_times"))) {
            stats.expectedTimes++;
            String sourceId = text(expectedTime, "source_turn_id");
            String raw = text(expectedTime, "raw");
            List<Map<String, Object>> matches = actualFacts.stream().filter(item ->
                sourceId.equals(str(item, "source_turn_id")) && raw.equals(str(item, "raw_time_expression"))).toList();
            Map<String, Object> actualTime = matches.isEmpty() ? Map.of() : matches.get(matches.size() - 1);
            boolean correct = text(expectedTime, "status").equals(str(actualTime, "time_status"));
            if (expectedTime.has("normalized_start")) correct &= text(expectedTime, "normalized_start").equals(str(actualTime, "normalized_start"));
            stats.exactTimes += correct ? 1 : 0;
            if (text(expectedTime, "status").equals("ambiguous")) {
                stats.ambiguousTimes++;
                stats.ambiguousCorrect += correct ? 1 : 0;
            }
            if (!correct) failures.add(failure(sampleId, "TIME_MISMATCH", "expected=" + expectedTime + "; actual=" + actualTime));
        }

        if (sample.path("sensitive").asBoolean(false)) {
            stats.sensitiveCases++;
            boolean noSensitivePersisted = actualFacts.stream().noneMatch(item ->
                    MemoryContentSafety.looksSensitive(str(item, "value_text"))
                    || MemoryContentSafety.looksSensitive(str(item, "raw_text")))
                && storedText(jdbc, "SELECT insight FROM user_insight WHERE user_id=?", userId).stream().noneMatch(MemoryContentSafety::looksSensitive)
                && storedText(jdbc, "SELECT insight FROM llm_growth WHERE user_id=?", userId).stream().noneMatch(MemoryContentSafety::looksSensitive);
            stats.sensitiveBlocked += noSensitivePersisted ? 1 : 0;
            if (!noSensitivePersisted) failures.add(failure(sampleId, "SENSITIVE_CONTENT_STORED", "A sensitive value was persisted"));
        }
        stats.samplesCompleted++;
    }

    private static Map<String, Object> logicalSnapshot(JdbcTemplate jdbc, CuratorTurnStore store, String userId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("facts", jdbc.queryForList("SELECT predicate,value_text,value_json,scope,assertion,confidence,valid_from,valid_to,observed_at,event_timezone,raw_time_expression,normalized_start,normalized_end,time_precision,time_status,source_turn_id,raw_text,status,supersedes_id FROM memory_fact WHERE user_id=? ORDER BY id", userId));
        snapshot.put("profiles", jdbc.queryForList("SELECT slot_key,value,source_fact_id,confidence,valid_from,valid_to FROM user_profile_current WHERE user_id=? ORDER BY slot_key", userId));
        snapshot.put("insights", jdbc.queryForList("SELECT insight,context FROM user_insight WHERE user_id=? ORDER BY insight", userId));
        snapshot.put("growth", jdbc.queryForList("SELECT category,insight,context FROM llm_growth WHERE user_id=? ORDER BY category,insight", userId));
        snapshot.put("checkpoint", store.checkpoint(userId));
        snapshot.put("working_memory", safeWorkingMemory(store.getWorkingMemory(userId)));
        return snapshot;
    }

    private static Object safeWorkingMemory(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> value = JSON.readValue(json, new TypeReference<>() {});
            value.remove("updated_at");
            return value;
        } catch (Exception ignored) { return json; }
    }

    private static List<Map<String, Object>> facts(JdbcTemplate jdbc, String userId) {
        return jdbc.queryForList("SELECT predicate,value_text,value_json,scope,assertion,confidence,valid_from,valid_to,observed_at,event_timezone,raw_time_expression,normalized_start,normalized_end,time_precision,time_status,source_turn_id,raw_text,status,supersedes_id FROM memory_fact WHERE user_id=? ORDER BY id", userId);
    }

    private static List<Map<String, Object>> profiles(JdbcTemplate jdbc, String userId) {
        return jdbc.queryForList("SELECT slot_key,value,source_fact_id,confidence,valid_from,valid_to FROM user_profile_current WHERE user_id=? ORDER BY slot_key", userId);
    }

    private static List<String> storedText(JdbcTemplate jdbc, String sql, String userId) {
        return jdbc.query(sql, (rs, row) -> rs.getString(1), userId);
    }

    private static long queryCount(JdbcTemplate jdbc, String sql, String userId) {
        Long count = jdbc.queryForObject(sql, Long.class, userId);
        return count == null ? 0 : count;
    }

    private static List<JsonNode> readJsonl(Path path) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.isBlank()) rows.add(JSON.readTree(line));
        }
        return rows;
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeJsonl(Path path, List<Map<String, Object>> rows) throws Exception {
        StringBuilder output = new StringBuilder();
        for (Map<String, Object> row : rows) output.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(path, output.toString(), StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("Expected --name value arguments");
            result.put(args[i].substring(2), args[++i]);
        }
        return result;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }

    private static String envOrOption(Map<String, String> options, String option, String environment) {
        String value = options.get(option);
        return value == null || value.isBlank() ? System.getenv().getOrDefault(environment, "") : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private static String defaultText(JsonNode node, String field, String fallback) {
        String result = text(node, field);
        return result.isBlank() ? fallback : result;
    }

    private static List<JsonNode> children(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(result::add);
        return result;
    }

    private static Map<String, Object> object(JsonNode value) {
        return value == null || !value.isObject() ? new LinkedHashMap<>()
            : JSON.convertValue(value, new TypeReference<>() {});
    }

    private static Map<String, String> objectStringMap(JsonNode value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value != null && value.isObject()) value.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText("")));
        return result;
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String canonical(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[\\s，。；、,.!?！？;:：]+", "");
    }

    private static String factKey(String predicate, String value, String scope, String assertion) {
        return predicate + "|" + canonical(value) + "|" + scope.trim().toLowerCase(Locale.ROOT)
            + "|" + assertion.trim().toLowerCase(Locale.ROOT);
    }

    private static int intersection(Set<String> left, Set<String> right) {
        Set<String> copy = new HashSet<>(left);
        copy.retainAll(right);
        return copy.size();
    }

    private static int difference(Set<String> left, Set<String> right) {
        Set<String> copy = new HashSet<>(left);
        copy.removeAll(right);
        return copy.size();
    }

    private static Map<String, Object> failure(String sampleId, String code, String detail) {
        return Map.of("sample_id", sampleId, "reason_code", code, "detail", detail);
    }

    private static final class InjectedRollback extends RuntimeException {}

    private static final class RunStats {
        long truePositive, falsePositive, falseNegative;
        long profileCorrect, profileExpected, profileWritten;
        long exactTimes, expectedTimes, ambiguousCorrect, ambiguousTimes;
        long sourceFacts, completeSources, activeFacts, supersededFacts, duplicateCount;
        long sensitiveCases, sensitiveBlocked;
        long rollbackChecks, rollbackConsistent, replayChecks, replayConsistent;
        long samplesCompleted, llmRuns, curatorRunsRecorded;
        final List<Long> llmElapsedMs = new ArrayList<>();
        final List<Long> sampleElapsedMs = new ArrayList<>();

        Map<String, Object> metrics(int inputSamples, String provider) {
            double precision = ratio(truePositive, truePositive + falsePositive, 1.0);
            double recall = ratio(truePositive, truePositive + falseNegative, 1.0);
            Map<String, Object> latency = new LinkedHashMap<>();
            latency.put("runs", llmRuns);
            latency.put("mean_ms", llmElapsedMs.isEmpty() ? 0 : llmElapsedMs.stream().mapToLong(Long::longValue).average().orElse(0));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", provider);
            result.put("input_samples", inputSamples);
            result.put("samples_completed", samplesCompleted);
            result.put("fact_precision", precision);
            result.put("fact_recall", recall);
            result.put("fact_f1", precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall));
            result.put("profile_precision", ratio(profileCorrect, profileWritten, profileExpected == 0 ? 1.0 : 0.0));
            result.put("profile_recall", ratio(profileCorrect, profileExpected, 1.0));
            result.put("time_exact_match", ratio(exactTimes, expectedTimes, 1.0));
            result.put("ambiguous_time_accuracy", ratio(ambiguousCorrect, ambiguousTimes, 1.0));
            result.put("source_completeness", ratio(completeSources, sourceFacts, 1.0));
            result.put("active_fact_rows", activeFacts);
            result.put("superseded_fact_rows", supersededFacts);
            result.put("duplicate_rate", ratio(duplicateCount, activeFacts, 0.0));
            result.put("sensitive_block_rate", ratio(sensitiveBlocked, sensitiveCases, 1.0));
            result.put("transaction_rollback_checks", rollbackChecks);
            result.put("transaction_rollback_consistency", rollbackChecks == 0 ? null
                : ratio(rollbackConsistent, rollbackChecks, 0.0));
            result.put("replay_checks", replayChecks);
            result.put("replay_consistency", replayChecks == 0 ? null
                : ratio(replayConsistent, replayChecks, 0.0));
            result.put("curator_runs_recorded", curatorRunsRecorded);
            Map<String, Object> processingLatency = new LinkedHashMap<>();
            processingLatency.put("samples", sampleElapsedMs.size());
            processingLatency.put("p50_ms", percentile(sampleElapsedMs, 0.50));
            processingLatency.put("p95_ms", percentile(sampleElapsedMs, 0.95));
            processingLatency.put("p99_ms", percentile(sampleElapsedMs, 0.99));
            processingLatency.put("mean_ms", sampleElapsedMs.stream().mapToLong(Long::longValue).average().orElse(0));
            result.put("sample_processing_latency_ms", processingLatency);
            result.put("llm_end_to_end_latency", latency);
            return result;
        }

        private static double ratio(long numerator, long denominator, double emptyValue) {
            return denominator == 0 ? emptyValue : (double) numerator / denominator;
        }

        private static long percentile(List<Long> values, double quantile) {
            if (values.isEmpty()) return 0;
            List<Long> sorted = values.stream().sorted().toList();
            int index = (int) Math.ceil(quantile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        }
    }
}
