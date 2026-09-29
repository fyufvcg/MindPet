package service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import util.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomically commits curator proposals, their projections, working memory, and batch state. */
@Service
public class MemoryCuratorCommitService {

    private static final int MAX_EVIDENCE_LENGTH = 1200;

    private final MemoryFactService factService;
    private final ProfileProjectionService profileProjectionService;
    private final UserInsightService insightService;
    private final CuratorTurnStore turnStore;
    private final Logger logger;
    private final Clock clock;

    public record CommitResult(int saved, Map<String, Integer> rejections) {
        public CommitResult {
            rejections = Collections.unmodifiableMap(new LinkedHashMap<>(rejections));
        }

        public int rejectedCount() {
            return rejections.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public static final class ProposalRejectedException extends RuntimeException {
        private final Map<String, Integer> rejections;

        public ProposalRejectedException(Map<String, Integer> rejections) {
            super("馆长提案包含可修复的校验错误，批次未提交");
            this.rejections = Collections.unmodifiableMap(new LinkedHashMap<>(rejections));
        }

        public Map<String, Integer> rejections() { return rejections; }
    }

    private static final class RejectionCounts {
        private final Map<String, Integer> values = new LinkedHashMap<>();

        void add(String reason) { values.merge(reason, 1, Integer::sum); }

        Map<String, Integer> snapshot() { return new LinkedHashMap<>(values); }
    }

    public MemoryCuratorCommitService(MemoryFactService factService,
                                      ProfileProjectionService profileProjectionService,
                                      UserInsightService insightService,
                                      CuratorTurnStore turnStore,
                                      Logger logger) {
        this(factService, profileProjectionService, insightService, turnStore, logger, Clock.systemDefaultZone());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryCuratorCommitService(MemoryFactService factService,
                                      ProfileProjectionService profileProjectionService,
                                      UserInsightService insightService,
                                      CuratorTurnStore turnStore,
                                      Logger logger,
                                      Clock clock) {
        this.factService = factService;
        this.profileProjectionService = profileProjectionService;
        this.insightService = insightService;
        this.turnStore = turnStore;
        this.logger = logger;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional
    public CommitResult commit(String userId, Map<String, Object> proposal,
                               List<CuratorTurnStore.CompletedTurn> turns,
                               long targetSequence, Map<String, byte[]> embeddings) {
        Map<String, CuratorTurnStore.CompletedTurn> byId = new LinkedHashMap<>();
        Map<String, Long> sequenceById = new LinkedHashMap<>();
        for (CuratorTurnStore.CompletedTurn turn : turns) {
            byId.put(turn.turnId(), turn);
            sequenceById.put(turn.turnId(), turnStore.sequenceFor(userId, turn.turnId()));
        }

        RejectionCounts rejectionCounts = new RejectionCounts();
        int saved = 0;
        LinkedHashSet<String> currentTopics = new LinkedHashSet<>();
        List<String> statusFacts = new ArrayList<>();
        Object factsValue = proposal.get("facts");
        if (factsValue instanceof List<?> facts) {
            List<Map<String, Object>> orderedFacts = new ArrayList<>();
            for (Object item : facts) {
                if (item instanceof Map<?, ?> raw) orderedFacts.add(toStringMap(raw));
                else rejectionCounts.add("malformed_fact_item");
            }
            orderedFacts.sort(Comparator
                .comparing((Map<String, Object> fact) -> occurredAt(text(fact.get("source_turn_id")), byId))
                .thenComparingLong(fact -> sequenceById.getOrDefault(text(fact.get("source_turn_id")), Long.MAX_VALUE))
                .thenComparing(fact -> text(fact.get("predicate")))
                .thenComparing(fact -> text(fact.get("value"))));
            for (Map<String, Object> fact : orderedFacts) {
                String sourceTurnId = text(fact.get("source_turn_id"));
                String evidence = text(fact.get("evidence"));
                String value = text(fact.get("value"));
                if (sourceTurnId.isBlank() || evidence.isBlank() || value.isBlank()) {
                    rejectionCounts.add("malformed_fact_fields");
                    continue;
                }
                CuratorTurnStore.CompletedTurn source = byId.get(sourceTurnId);
                if (source == null) {
                    rejectionCounts.add("unknown_source_turn");
                    continue;
                }
                if (evidence.length() > MAX_EVIDENCE_LENGTH) {
                    rejectionCounts.add("evidence_too_long");
                    continue;
                }
                if (!MemoryContentSafety.supportsExactEvidence(source.userMessage(), evidence)) {
                    rejectionCounts.add("evidence_not_verbatim");
                    continue;
                }
                String normalizedEvidence = MemoryContentSafety.normalizeEvidence(evidence);
                String normalizedValue = MemoryContentSafety.normalizeEvidence(value);
                if (!normalizedEvidence.contains(normalizedValue)) {
                    rejectionCounts.add("value_not_in_evidence");
                    continue;
                }

                String predicate = text(fact.get("predicate"));
                String scope = text(fact.get("scope")).toLowerCase(java.util.Locale.ROOT);
                String assertion = text(fact.get("assertion")).toLowerCase(java.util.Locale.ROOT);
                if (!MemoryFactOntology.supports(predicate, scope, assertion)) {
                    rejectionCounts.add("invalid_fact_metadata");
                    continue;
                }
                Double confidenceValue = confidence(fact.get("confidence"));
                if (confidenceValue == null) {
                    rejectionCounts.add("invalid_confidence");
                    continue;
                }
                double confidence = confidenceValue;
                if (!Double.isFinite(confidence) || confidence > 1.0 || confidence < 0.55) {
                    rejectionCounts.add("confidence_out_of_policy");
                    continue;
                }

                Map<String, Object> time = fact.get("time") instanceof Map<?, ?> rawTime
                    ? toStringMap(rawTime) : Map.of();
                String rawTime = text(time.get("raw"));
                ZoneId zone = zone(source.eventTimezone());
                TemporalNormalizer.Resolution resolution = TemporalNormalizer.resolve(
                    rawTime, parseInstant(source.occurredAt(), source.completedAt()), zone);
                String normalizedStart = resolution.resolved() ? resolution.normalizedStart() : "";
                String normalizedEnd = resolution.resolved() ? resolution.normalizedEnd() : "";
                String cleanEvidence = MemoryContentSafety.normalizeEvidence(evidence);
                String valueJson = text(fact.get("value_json"));
                if (MemoryContentSafety.looksSensitive(cleanEvidence)
                        || MemoryContentSafety.looksSensitive(value)
                        || MemoryContentSafety.looksSensitive(valueJson)) {
                    rejectionCounts.add("sensitive_content");
                    continue;
                }
                if (!MemoryContentSafety.polarityConsistent(cleanEvidence, value, assertion)) {
                    rejectionCounts.add("polarity_mismatch_or_uncertainty");
                    continue;
                }

                MemoryFactService.FactCandidate candidate = new MemoryFactService.FactCandidate(
                    predicate, value, valueJson, scope, assertion, confidence,
                    normalizedStart, normalizedEnd, source.occurredAt(), zone.getId(), rawTime,
                    normalizedStart, normalizedEnd, resolution.precision(), resolution.status(),
                    source.turnId(), cleanEvidence, sequenceById.getOrDefault(source.turnId(), 0L));
                MemoryFactService.SavedFact result = factService.merge(userId, candidate);
                if (result.id() <= 0) {
                    rejectionCounts.add("storage_validation_rejected");
                    continue;
                }
                if (result.inserted()) saved++;
                // Reconcile even on deduplication: a previous partial run may have saved the fact only.
                profileProjectionService.projectFact(userId, result.id());

                boolean currentProject = "current_project".equals(predicate) && "current".equals(scope)
                    && Set.of("observed", "confirmed").contains(assertion);
                boolean plannedTopic = "plan".equals(predicate) && "planned".equals(assertion);
                boolean upcomingEvent = "event".equals(predicate)
                    && ("planned".equals(assertion) || isUpcoming(normalizedStart, zone));
                if (currentProject || plannedTopic || upcomingEvent) {
                    String label = switch (predicate) {
                        case "current_project" -> "当前项目";
                        case "plan" -> "计划";
                        default -> "近期事项";
                    };
                    statusFacts.add(label + "：" + value);
                    if ((plannedTopic || upcomingEvent) && value.length() <= 120) {
                        currentTopics.add(value);
                    }
                }
            }
        } else if (factsValue != null) {
            rejectionCounts.add("malformed_facts_section");
        }

        saved += saveInsights(userId, proposal.get("insights"), byId, embeddings, rejectionCounts);
        saved += saveGrowth(userId, proposal.get("growth"), byId, embeddings, rejectionCounts);

        Map<String, Integer> rejections = rejectionCounts.snapshot();
        if (hasRetryableRejection(rejections)) throw new ProposalRejectedException(rejections);

        String summary = String.join("；", statusFacts);
        if (summary.codePointCount(0, summary.length()) > 200) {
            summary = summary.substring(0, summary.offsetByCodePoints(0, 200));
        }
        List<String> topics = currentTopics.stream().limit(3).toList();
        Map<String, Object> workingMemory = new LinkedHashMap<>();
        workingMemory.put("version", 2);
        workingMemory.put("summary", summary);
        workingMemory.put("open_topics", topics);
        workingMemory.put("current_emotion", "neutral");
        workingMemory.put("checkpoint", targetSequence);
        workingMemory.put("updated_at", clock.instant().toString());
        try {
            turnStore.saveWorkingMemory(userId, workingMemory);
        } catch (Exception e) {
            throw new IllegalStateException("保存馆长工作记忆失败", e);
        }

        if (turns.isEmpty()) throw new IllegalArgumentException("不能提交空的馆长批次");
        String lastTurnId = turns.get(turns.size() - 1).turnId();
        long lastSequence = turnStore.sequenceFor(userId, lastTurnId);
        if (lastSequence <= 0) throw new IllegalStateException("馆长批次的末尾回合已不存在");
        turnStore.markProcessed(userId, turns, "success");
        turnStore.saveCheckpoint(userId, Math.max(targetSequence, lastSequence), lastTurnId);
        if (!rejections.isEmpty()) {
            logger.log("WARN", "记忆馆长提案已按策略过滤部分条目：" + rejections);
        }
        return new CommitResult(saved, rejections);
    }

    private int saveInsights(String userId, Object value,
                             Map<String, CuratorTurnStore.CompletedTurn> byId,
                             Map<String, byte[]> embeddings,
                             RejectionCounts rejectionCounts) {
        if (!(value instanceof List<?> items)) {
            if (value != null) rejectionCounts.add("malformed_insights_section");
            return 0;
        }
        int saved = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                rejectionCounts.add("malformed_insight_item");
                continue;
            }
            Map<String, Object> insight = toStringMap(raw);
            String text = text(insight.get("insight"));
            String evidence = supportedCitationEvidence(insight, byId);
            String context = MemoryContentSafety.normalizeEvidence(evidence);
            if (context.isBlank()) {
                rejectionCounts.add("unsupported_insight_evidence");
                continue;
            }
            if (text.isBlank() || text.length() > 800) {
                rejectionCounts.add("invalid_insight_content");
                continue;
            }
            if (!safe(text, context)) {
                rejectionCounts.add("sensitive_insight_content");
                continue;
            }
            if (insightService.insightExists(userId, text)) continue;
            if (insightService.savePreparedInsight(userId, text, context, embeddings.get(text))) {
                saved++;
            } else if (!insightService.insightExists(userId, text)) {
                throw new IllegalStateException("保存相处经验失败，批次保留待重试");
            }
        }
        return saved;
    }

    private int saveGrowth(String userId, Object value,
                           Map<String, CuratorTurnStore.CompletedTurn> byId,
                           Map<String, byte[]> embeddings,
                           RejectionCounts rejectionCounts) {
        if (!(value instanceof List<?> items)) {
            if (value != null) rejectionCounts.add("malformed_growth_section");
            return 0;
        }
        int saved = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                rejectionCounts.add("malformed_growth_item");
                continue;
            }
            Map<String, Object> growth = toStringMap(raw);
            String category = text(growth.get("category"));
            String insight = text(growth.get("insight"));
            String evidence = supportedCitationEvidence(growth, byId);
            String context = MemoryContentSafety.normalizeEvidence(evidence);
            if (category.isBlank() || category.length() > 80
                    || insight.isBlank() || insight.length() > 800) {
                rejectionCounts.add("invalid_growth_content");
                continue;
            }
            if (context.isBlank()) {
                rejectionCounts.add("unsupported_growth_evidence");
                continue;
            }
            if (!safe(category, insight) || !safe(category, context) || !safe(insight, context)) {
                rejectionCounts.add("sensitive_growth_content");
                continue;
            }
            if (insightService.growthExists(userId, category, insight)) continue;
            if (insightService.savePreparedGrowth(userId, category, insight, context, embeddings.get(insight))) {
                saved++;
            } else if (!insightService.growthExists(userId, category, insight)) {
                throw new IllegalStateException("保存成长经验失败，批次保留待重试");
            }
        }
        return saved;
    }

    private String supportedCitationEvidence(Map<String, Object> item,
                                             Map<String, CuratorTurnStore.CompletedTurn> byId) {
        String evidence = text(item.get("evidence"));
        Object citations = item.get("source_turn_ids");
        if (!(citations instanceof List<?> ids) || evidence.isBlank()) return "";
        for (Object id : ids) {
            CuratorTurnStore.CompletedTurn turn = byId.get(text(id));
            if (turn != null && evidence.length() <= MAX_EVIDENCE_LENGTH
                    && MemoryContentSafety.supportsExactEvidence(turn.userMessage(), evidence)) return evidence;
        }
        return "";
    }

    private boolean safe(String first, String second) {
        return !MemoryContentSafety.looksSensitive(first) && !MemoryContentSafety.looksSensitive(second);
    }

    private String occurredAt(String turnId,
                              Map<String, CuratorTurnStore.CompletedTurn> byId) {
        CuratorTurnStore.CompletedTurn turn = byId.get(turnId);
        return turn == null ? "" : text(turn.occurredAt());
    }

    private boolean hasRetryableRejection(Map<String, Integer> rejections) {
        Set<String> terminal = Set.of(
            "sensitive_content", "sensitive_insight_content", "sensitive_growth_content");
        return rejections.keySet().stream().anyMatch(reason -> !terminal.contains(reason));
    }

    private boolean isUpcoming(String normalizedStart, ZoneId zone) {
        try {
            return !normalizedStart.isBlank()
                && !java.time.LocalDate.parse(normalizedStart).isBefore(java.time.LocalDate.now(clock.withZone(zone)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Map<String, Object> toStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static Double confidence(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(text(value)); } catch (Exception ignored) { return null; }
    }

    private Instant parseInstant(String value, String fallback) {
        try { return Instant.parse(value); } catch (Exception ignored) {
            try { return Instant.parse(fallback); } catch (Exception ignoredAgain) { return clock.instant(); }
        }
    }

    private ZoneId zone(String value) {
        try { return value == null || value.isBlank() ? clock.getZone() : ZoneId.of(value); }
        catch (Exception ignored) { return clock.getZone(); }
    }
}
