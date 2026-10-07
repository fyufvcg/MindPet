package service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import util.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.text.Normalizer;
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
    private static final int MAX_RETIREMENTS_PER_BATCH = 10;
    private static final int MAX_FACT_SOURCES = 5;

    private final MemoryFactService factService;
    private final ProfileProjectionService profileProjectionService;
    private final UserInsightService insightService;
    private final CuratorTurnStore turnStore;
    private final Logger logger;
    private final Clock clock;
    private final MemoryCorpusCompactionService corpusCompactionService;

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
        private final List<Map<String,Object>> diagnostics;

        public ProposalRejectedException(Map<String, Integer> rejections) {
            this(rejections,List.of());
        }

        public ProposalRejectedException(Map<String, Integer> rejections, List<Map<String,Object>> diagnostics) {
            super("馆长提案包含可修复的校验错误，批次未提交");
            this.rejections = Collections.unmodifiableMap(new LinkedHashMap<>(rejections));
            this.diagnostics = List.copyOf(diagnostics);
        }

        public Map<String, Integer> rejections() { return rejections; }
        public List<Map<String,Object>> diagnostics() { return diagnostics; }
    }

    private static final class RejectionCounts {
        private final Map<String, Integer> values = new LinkedHashMap<>();
        private final List<Map<String,Object>> details = new ArrayList<>();
        private String section = "facts";
        private int itemIndex = -1, sourceIndex = -1;
        private String turnId = "", quote = "";

        void item(String section, int index) { this.section=section; itemIndex=index; sourceIndex=-1;turnId="";quote=""; }
        void source(int index,String turn,String text) {sourceIndex=index;turnId=turn;quote=text;}

        void add(String reason) {
            if (details.stream().anyMatch(d -> section.equals(d.get("section")) && itemIndex==((Number)d.get("item_index")).intValue()
                    && sourceIndex==((Number)d.get("source_index")).intValue() && reason.equals(d.get("reason_code")))) return;
            values.merge(reason, 1, Integer::sum);
            details.add(Map.of("section",section,"item_index",itemIndex,"source_index",sourceIndex,
                "turn_id",turnId,"quote",MemoryContentSafety.looksSensitive(quote)?"[redacted]":quote,"reason_code",reason));
        }

        Map<String, Integer> snapshot() { return new LinkedHashMap<>(values); }
        List<Map<String,Object>> details() { return List.copyOf(details); }
    }

    /** Extraction errors can be isolated after one directed repair; transaction errors still roll back. */
    private final ThreadLocal<Boolean> allowPartial = ThreadLocal.withInitial(() -> false);

    @Transactional
    public CommitResult commitPartial(String userId, Map<String,Object> proposal,
            List<CuratorTurnStore.CompletedTurn> turns,long sequence,Map<String,byte[]> embeddings) {
        allowPartial.set(true);
        try {return commit(userId,proposal,turns,sequence,embeddings);}
        finally {allowPartial.remove();}
    }

    public MemoryCuratorCommitService(MemoryFactService factService,
                                      ProfileProjectionService profileProjectionService,
                                      UserInsightService insightService,
                                      CuratorTurnStore turnStore,
                                      Logger logger) {
        this(factService, profileProjectionService, insightService, turnStore, logger,
            Clock.systemDefaultZone(), null);
    }

    public MemoryCuratorCommitService(MemoryFactService factService,
                                      ProfileProjectionService profileProjectionService,
                                      UserInsightService insightService,
                                      CuratorTurnStore turnStore,
                                      Logger logger,
                                      Clock clock) {
        this(factService, profileProjectionService, insightService, turnStore, logger, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryCuratorCommitService(MemoryFactService factService,
                                      ProfileProjectionService profileProjectionService,
                                      UserInsightService insightService,
                                      CuratorTurnStore turnStore,
                                      Logger logger,
                                      Clock clock,
                                      MemoryCorpusCompactionService corpusCompactionService) {
        this.factService = factService;
        this.profileProjectionService = profileProjectionService;
        this.insightService = insightService;
        this.turnStore = turnStore;
        this.logger = logger;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.corpusCompactionService = corpusCompactionService;
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
        Map<String,Map<String,Object>> acceptedEvents=new LinkedHashMap<>();
        LinkedHashSet<String> currentTopics = new LinkedHashSet<>();
        List<String> statusFacts = new ArrayList<>();
        Object factsValue = proposal.get("facts");
        List<PreparedFact> preparedFacts = new ArrayList<>();
        if (factsValue instanceof List<?> facts) {
            List<Map<String, Object>> orderedFacts = new ArrayList<>();
            for (int itemIndex=0;itemIndex<facts.size();itemIndex++) {
                Object item=facts.get(itemIndex);rejectionCounts.item("facts",itemIndex);
                if (item instanceof Map<?, ?> raw) {
                    Map<String,Object> copy=toStringMap(raw);
                    copy.put("_item_index",itemIndex); orderedFacts.add(copy);
                }
                else rejectionCounts.add("malformed_fact_item");
            }
            orderedFacts.sort(Comparator
                .comparing((Map<String, Object> fact) -> occurredAt(primarySourceTurnId(fact, byId, sequenceById), byId))
                .thenComparingLong(fact -> sequenceById.getOrDefault(
                    primarySourceTurnId(fact, byId, sequenceById), Long.MAX_VALUE))
                .thenComparing(fact -> text(fact.get("predicate")))
                .thenComparing(fact -> text(fact.get("value"))));
            for (Map<String, Object> fact : orderedFacts) {
                rejectionCounts.item("facts",((Number)fact.get("_item_index")).intValue());
                String value = MemoryCuratorFactSupport.canonicalValue(text(fact.get("predicate")),
                    text(fact.getOrDefault("canonical_value",fact.get("value"))));
                String proposedAction = text(fact.get("action")).toUpperCase(java.util.Locale.ROOT);
                Object replacesValue = fact.get("replaces_unit_ids");
                List<String> replacesUnitIds = stringList(replacesValue);
                if (value.isBlank()) {
                    rejectionCounts.add("malformed_fact_fields");
                    continue;
                }
                if (!Set.of("KEEP", "MERGE", "SUPERSEDE", "RETIRE", "NOOP").contains(proposedAction)
                        || !isValidStringList(replacesValue)) {
                    // Advisory fields do not authorize or veto deterministic compaction.
                    proposedAction = "UNSPECIFIED";
                    replacesUnitIds = List.of();
                }
                String predicate = text(fact.get("predicate"));
                String scope = text(fact.get("scope")).toLowerCase(java.util.Locale.ROOT);
                String assertion = text(fact.get("assertion")).toLowerCase(java.util.Locale.ROOT);
                if (!MemoryFactOntology.supports(predicate, scope, assertion)) {
                    rejectionCounts.add("invalid_fact_metadata");
                    continue;
                }
                List<FactSource> sources = parseFactSources(fact, byId, sequenceById, value, scope, assertion,
                    rejectionCounts);
                if (sources.isEmpty()) continue;
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
                String valueJson = text(fact.get("value_json"));
                if (MemoryContentSafety.looksSensitive(value)
                        || MemoryContentSafety.looksSensitive(valueJson)) {
                    rejectionCounts.add("sensitive_content");
                    continue;
                }
                // Process each observed mention in global event order. Grouped A sources on both
                // sides of B must not erase the original A interval or attach it to the returned A.
                for (FactSource mention : sources) {
                    CuratorTurnStore.CompletedTurn observed = mention.turn();
                    ZoneId sourceZone = zone(observed.eventTimezone());
                    TemporalNormalizer.Resolution sourceTime = TemporalNormalizer.resolve(rawTime,
                        parseInstant(observed.occurredAt(), observed.completedAt()), sourceZone);
                    String start = sourceTime.resolved() ? sourceTime.normalizedStart() : "";
                    String end = sourceTime.resolved() ? sourceTime.normalizedEnd() : "";
                    String mentionAssertion = MemoryFactOntology.canonicalAssertion(assertion, mention.evidence());
                    MemoryFactService.FactCandidate candidate = new MemoryFactService.FactCandidate(
                        predicate, value, valueJson, scope, mentionAssertion, confidence,
                        start, end, observed.occurredAt(), sourceZone.getId(), rawTime,
                        start, end, sourceTime.precision(), sourceTime.status(), observed.turnId(),
                        MemoryContentSafety.normalizeEvidence(mention.evidence()), sequenceById.getOrDefault(observed.turnId(), 0L));
                    preparedFacts.add(new PreparedFact(candidate, List.of(mention), proposedAction, replacesUnitIds,
                        text(fact.get("retrieval_text"))));
                }
            }
        } else if (factsValue != null) {
            rejectionCounts.add("malformed_facts_section");
        }
        saveInsights(userId, proposal.get("insights"), byId, embeddings, rejectionCounts, targetSequence, false);
        saveGrowth(userId, proposal.get("growth"), byId, embeddings, rejectionCounts, targetSequence, false);
        // Do not apply only half of an unresolved transition in the same state slot.
        if (allowPartial.get() && factsValue instanceof List<?> facts) {
            Set<String> blockedSlots=new LinkedHashSet<>();
            for(Map<String,Object> detail:rejectionCounts.details()) {
                int at=((Number)detail.get("item_index")).intValue();
                if (!"facts".equals(detail.get("section")) || at<0 || at>=facts.size() || !(facts.get(at) instanceof Map<?,?> raw)) continue;
                String predicate=text(raw.get("predicate")), scope=text(raw.get("scope"));
                String val=MemoryCuratorFactSupport.canonicalValue(predicate,text(raw.get("canonical_value")==null?raw.get("value"):raw.get("canonical_value")));
                boolean hasSupportedSource=preparedFacts.stream().anyMatch(p->p.candidate().predicate().equals(predicate)
                    && p.candidate().value().equals(val) && p.candidate().scope().equals(scope));
                if(!hasSupportedSource && (MemoryFactOntology.isProfileSlot(predicate) || Set.of("plan","event").contains(predicate)))
                    blockedSlots.add(predicate+"|"+(Set.of("plan","event").contains(predicate)?val:scope));
            }
            preparedFacts.removeIf(p->blockedSlots.contains(p.candidate().predicate()+"|"+
                (Set.of("plan","event").contains(p.candidate().predicate())?p.candidate().value():p.candidate().scope())));
            for(int at=0;at<facts.size();at++) if(facts.get(at) instanceof Map<?,?> raw
                    && blockedSlots.contains(text(raw.get("predicate"))+"|"+(Set.of("plan","event").contains(text(raw.get("predicate")))
                        ?MemoryCuratorFactSupport.canonicalValue(text(raw.get("predicate")),text(raw.get("canonical_value")==null?raw.get("value"):raw.get("canonical_value"))):text(raw.get("scope"))))) {
                rejectionCounts.item("facts",at);rejectionCounts.add("dependency_slot_pending");
            }
        }
        preparedFacts.sort(Comparator.comparing((PreparedFact fact) -> fact.candidate().observedAt())
            .thenComparingLong(fact -> fact.candidate().sourceSequence())
            .thenComparing(fact -> fact.candidate().predicate()).thenComparing(fact -> fact.candidate().value()));
        // Validate all extraction sections before the first database mutation.
        if (!allowPartial.get() && hasRetryableRejection(rejectionCounts.snapshot())) {
            throw new ProposalRejectedException(rejectionCounts.snapshot(),rejectionCounts.details());
        }
        if (corpusCompactionService != null) {
            corpusCompactionService.ensureLegacyIndexed(userId);
            corpusCompactionService.beginBatch(userId, targetSequence);
            saved += applyRetirements(userId, proposal.get("retire"), rejectionCounts, targetSequence);
            if (!allowPartial.get() && hasRetryableRejection(rejectionCounts.snapshot())) throw new ProposalRejectedException(rejectionCounts.snapshot(),rejectionCounts.details());
        }
        for (PreparedFact prepared : preparedFacts) {
            MemoryFactService.FactCandidate candidate = prepared.candidate();
            String predicate = candidate.predicate();
            String value = candidate.value();
            String scope = candidate.scope();
            String assertion = candidate.assertion();
            String normalizedStart = candidate.normalizedStart();
            ZoneId zone = zone(candidate.timezone());
            List<FactSource> sources = prepared.sources();
            List<String> activeUnitsBefore = corpusCompactionService == null ? List.of()
                : corpusCompactionService.activeFactUnitIds(userId, predicate, scope,
                    "negated".equals(assertion) ? value : null);
            if (corpusCompactionService != null) {
                corpusCompactionService.snapshotFactSlot(userId, predicate, scope, targetSequence);
                if ("episodic".equals(scope) && Set.of("observed", "confirmed").contains(assertion)) {
                    corpusCompactionService.snapshotFactSlot(userId, predicate, "planned", targetSequence);
                }
            }
            MemoryFactService.SavedFact result = factService.merge(userId, candidate);
            if (result.id() <= 0) {
                throw new IllegalStateException("已验证事实未能保存，事务回滚");
            }
            if (result.inserted() && corpusCompactionService != null) {
                corpusCompactionService.recordInsertedFact(userId, result.id(), targetSequence);
            }
            String baseAction = "negated".equals(assertion) ? "RETIRE"
                : result.supersededId() != null ? "SUPERSEDE"
                : !result.inserted() ? "MERGE" : "KEEP";
            String retrievalText = MemoryCuratorFactSupport.retrievalText(
                predicate, value, scope, assertion, candidate.rawText());
            if (corpusCompactionService != null) corpusCompactionService.reconcileFactStatuses(userId, targetSequence);
            corpusCompactionServiceSafeSync(userId, result.id(), retrievalText,
                embeddings.get(retrievalText), targetSequence);
            profileProjectionService.projectFact(userId, result.id());
            for (FactSource factSource : sources) {
                corpusCompactionServiceSafeRecordEvidence(userId, result.id(), factSource,
                    targetSequence);
            }

            List<String> requiredReplacements = new ArrayList<>();
            if (corpusCompactionService != null) {
                if ("SUPERSEDE".equals(baseAction)) {
                    for (String unitId : activeUnitsBefore) {
                        if (corpusCompactionService.factUnitHasStatus(userId, unitId, "superseded")) {
                            requiredReplacements.add(unitId);
                        }
                    }
                } else if ("RETIRE".equals(baseAction)) {
                    for (String unitId : activeUnitsBefore) {
                        if (corpusCompactionService.factUnitHasStatus(userId, unitId, "superseded")) {
                            requiredReplacements.add(unitId);
                        }
                    }
                } else if ("MERGE".equals(baseAction)) {
                    String mergedUnitId = corpusCompactionService.unitIdForFact(userId, result.id());
                    if (!mergedUnitId.isBlank()
                            && corpusCompactionService.isMergeableFactUnit(userId, mergedUnitId, result.id())) {
                        requiredReplacements.add(mergedUnitId);
                    }
                }

                corpusCompactionService.recordActionResolution(userId, targetSequence,
                    corpusCompactionService.unitIdForFact(userId, result.id()), prepared.proposedAction(), baseAction,
                    prepared.replacesUnitIds(), requiredReplacements);
            } else if (!"KEEP".equals(baseAction)) {
                rejectionCounts.add("compaction_service_unavailable");
            }
            if (result.inserted()) saved++;
            Map<String,Object> event = new LinkedHashMap<>();
            event.put("type","fact");event.put("content",retrievalText);event.put("predicate",predicate);
            event.put("value",value);event.put("scope",scope);event.put("assertion",assertion);
            event.put("source_turn_ids",sources.stream().map(s->s.turn().turnId()).toList());
            event.put("evidence",sources.stream().map(FactSource::evidence).toList());
            event.put("valid_from",candidate.validFrom());event.put("normalized_start",candidate.normalizedStart());
            event.put("time_status",candidate.timeStatus());
            event.put("observed_at",candidate.observedAt());event.put("fact_id",result.id());
            // The physical fact ID identifies an interval; A -> B -> A produces distinct IDs.
            String eventKey="fact:"+targetSequence+":"+result.id();
            Map<String,Object> previousEvent=acceptedEvents.get(eventKey);
            if(previousEvent!=null) {
                List<String> ids=new ArrayList<>((List<String>)previousEvent.get("source_turn_ids"));
                List<String> quotes=new ArrayList<>((List<String>)previousEvent.get("evidence"));
                for(FactSource source:sources) if(!ids.contains(source.turn().turnId())) {ids.add(source.turn().turnId());quotes.add(source.evidence());}
                previousEvent.put("source_turn_ids",ids);previousEvent.put("evidence",quotes);
                previousEvent.put("observed_at",candidate.observedAt());
            } else acceptedEvents.put(eventKey,event);

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
        for(var entry:acceptedEvents.entrySet()) turnStore.recordAcceptedEvent(userId,targetSequence,entry.getKey(),entry.getValue());
        saved += saveInsights(userId, proposal.get("insights"), byId, embeddings, rejectionCounts, targetSequence, true);
        saved += saveGrowth(userId, proposal.get("growth"), byId, embeddings, rejectionCounts, targetSequence, true);
        if (corpusCompactionService == null) saved += applyRetirements(userId, proposal.get("retire"), rejectionCounts, targetSequence);
        if (corpusCompactionService != null) {
            corpusCompactionService.reconcileFactStatuses(userId, targetSequence);
            MemoryCorpusCompactionService.CompactionPlan plan = corpusCompactionService.planCompaction(userId, targetSequence);
            corpusCompactionService.applyCompactionPlan(plan);
        }

        Map<String, Integer> rejections = rejectionCounts.snapshot();
        if (!allowPartial.get() && hasRetryableRejection(rejections)) throw new ProposalRejectedException(rejections,rejectionCounts.details());
        turnStore.recordProposalItems(userId,targetSequence,proposal,rejectionCounts.details());

        if (corpusCompactionService != null) {
            statusFacts.clear();
            currentTopics.clear();
            for (MemoryCorpusCompactionService.RetrievalUnit unit : corpusCompactionService.currentWorkingFacts(userId)) {
                if (!"fact".equals(unit.unitType()) || !"active".equals(unit.status())
                        || "negated".equals(unit.assertion())) continue;
                if ("current_project".equals(unit.predicate()) || "planned".equals(unit.scope())) {
                    statusFacts.add(unit.content());
                    currentTopics.add(unit.value());
                }
            }
        }
        String summary = String.join("；", statusFacts);
        if (summary.codePointCount(0, summary.length()) > 200) {
            summary = summary.substring(0, summary.offsetByCodePoints(0, 200));
        }
        List<String> topics = currentTopics.stream().limit(3).toList();
        if (corpusCompactionService != null) {
            corpusCompactionService.snapshotProcessingState(userId, turns, targetSequence);
        }
        Map<String, Object> workingMemory = new LinkedHashMap<>();
        workingMemory.put("version", 2);
        workingMemory.put("summary", summary);
        workingMemory.put("open_topics", topics);
        workingMemory.put("current_emotion", "neutral");
        workingMemory.put("checkpoint",Math.max(targetSequence,turnStore.checkpoint(userId)));
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
        boolean unresolved=turnStore.pendingItems(userId).stream().anyMatch(item->((Number)item.get("batch_sequence")).longValue()==targetSequence);
        turnStore.markProcessed(userId, turns, unresolved?"partial":"success");
        if(targetSequence>=turnStore.checkpoint(userId))turnStore.saveCheckpoint(userId,Math.max(targetSequence,lastSequence),lastTurnId);
        if (corpusCompactionService != null) corpusCompactionService.completeBatch(userId, targetSequence);
        if (!rejections.isEmpty()) {
            logger.log("WARN", "记忆馆长提案已按策略过滤部分条目：" + rejections);
        }
        return new CommitResult(saved, rejections);
    }

    private void corpusCompactionServiceSafeSync(String userId, long factId, String retrievalText,
                                                 byte[] embedding, long targetSequence) {
        if (corpusCompactionService != null) {
            corpusCompactionService.syncFact(userId, factId, retrievalText, embedding, targetSequence);
        }
    }

    private void corpusCompactionServiceSafeRecordEvidence(String userId, long factId,
                                                            FactSource source, long targetSequence) {
        if (corpusCompactionService != null) {
            corpusCompactionService.recordFactEvidence(userId, factId, source.turn(), source.evidence(),
                source.surfaceValue(), targetSequence);
        }
    }

    private int saveInsights(String userId, Object value,
                             Map<String, CuratorTurnStore.CompletedTurn> byId,
                             Map<String, byte[]> embeddings,
                             RejectionCounts rejectionCounts, long targetSequence, boolean apply) {
        if (!(value instanceof List<?> items)) {
            if (value != null) rejectionCounts.add("malformed_insights_section");
            return 0;
        }
        int saved = 0;
        for (int itemIndex=0; itemIndex<items.size(); itemIndex++) {
            Object item=items.get(itemIndex);
            rejectionCounts.item("insights",itemIndex);
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
            if (MemoryCorpusCompactionService.tokenCount(text) > 80) {
                rejectionCounts.add("oversized_insight_content");
                continue;
            }
            if (!apply) continue;
            if (corpusCompactionService != null) corpusCompactionService.snapshotInsight(userId, text, targetSequence);
            boolean exists = insightService.insightExists(userId, text);
            if (!exists && insightService.savePreparedInsight(userId, text, context, embeddings.get(text))) {
                saved++;
            } else if (!exists && !insightService.insightExists(userId, text)) {
                throw new IllegalStateException("保存相处经验失败，批次保留待重试");
            }
            if (corpusCompactionService != null) {
                corpusCompactionService.syncInsight(userId, text, context, embeddings.get(text),
                    supportedCitationTurns(insight, byId, evidence), targetSequence);
            }
            turnStore.recordAcceptedEvent(userId,targetSequence,"insight:"+targetSequence+":"+text,
                Map.of("type","insight","content",text,"source_turn_ids",supportedCitationTurns(insight,byId,evidence).stream().map(CuratorTurnStore.CompletedTurn::turnId).toList(),"evidence",List.of(evidence)));
        }
        return saved;
    }

    private int applyRetirements(String userId, Object value,
                                 RejectionCounts rejectionCounts, long targetSequence) {
        if (value == null) return 0;
        if (!(value instanceof List<?> items)) {
            rejectionCounts.add("malformed_retire_section");
            return 0;
        }
        if (items.size() > MAX_RETIREMENTS_PER_BATCH) {
            rejectionCounts.add("too_many_retirements");
            return 0;
        }
        if (!items.isEmpty() && corpusCompactionService == null) {
            rejectionCounts.add("retirement_service_unavailable");
            return 0;
        }
        int retired = 0;
        Set<String> seenUnitIds = new LinkedHashSet<>();
        for (int itemIndex=0; itemIndex<items.size(); itemIndex++) {
            Object item=items.get(itemIndex);
            rejectionCounts.item("retire",itemIndex);
            if (!(item instanceof Map<?, ?> raw)) {
                rejectionCounts.add("malformed_retire_item");
                continue;
            }
            Map<String, Object> proposal = toStringMap(raw);
            String unitId = text(proposal.get("unit_id"));
            String reason = text(proposal.get("reason"));
            if (unitId.isBlank() || !seenUnitIds.add(unitId) || !"non_durable_noise".equals(reason)
                    || !corpusCompactionService.retireRawMemory(userId, unitId, reason, targetSequence)) {
                rejectionCounts.add("invalid_retirement_candidate");
                continue;
            }
            retired++;
        }
        return retired;
    }

    private int saveGrowth(String userId, Object value,
                           Map<String, CuratorTurnStore.CompletedTurn> byId,
                           Map<String, byte[]> embeddings,
                           RejectionCounts rejectionCounts, long targetSequence, boolean apply) {
        if (!(value instanceof List<?> items)) {
            if (value != null) rejectionCounts.add("malformed_growth_section");
            return 0;
        }
        int saved = 0;
        for (int itemIndex=0; itemIndex<items.size(); itemIndex++) {
            Object item=items.get(itemIndex);
            rejectionCounts.item("growth",itemIndex);
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
            if (MemoryCorpusCompactionService.tokenCount(insight) > 80) {
                rejectionCounts.add("oversized_growth_content");
                continue;
            }
            if (!apply) continue;
            if (corpusCompactionService != null) {
                corpusCompactionService.snapshotGrowth(userId, category, insight, targetSequence);
            }
            boolean exists = insightService.growthExists(userId, category, insight);
            if (!exists && insightService.savePreparedGrowth(userId, category, insight, context, embeddings.get(insight))) {
                saved++;
            } else if (!exists && !insightService.growthExists(userId, category, insight)) {
                throw new IllegalStateException("保存成长经验失败，批次保留待重试");
            }
            if (corpusCompactionService != null) {
                corpusCompactionService.syncGrowth(userId, category, insight, context, embeddings.get(insight),
                    supportedCitationTurns(growth, byId, evidence), targetSequence);
            }
            turnStore.recordAcceptedEvent(userId,targetSequence,"growth:"+targetSequence+":"+category+":"+insight,
                Map.of("type","growth","content","["+category+"] "+insight,"source_turn_ids",supportedCitationTurns(growth,byId,evidence).stream().map(CuratorTurnStore.CompletedTurn::turnId).toList(),"evidence",List.of(evidence)));
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

    private List<CuratorTurnStore.CompletedTurn> supportedCitationTurns(
            Map<String, Object> item, Map<String, CuratorTurnStore.CompletedTurn> byId, String evidence) {
        List<CuratorTurnStore.CompletedTurn> result = new ArrayList<>();
        Object citations = item.get("source_turn_ids");
        if (!(citations instanceof List<?> ids) || evidence == null || evidence.isBlank()) return result;
        for (Object id : ids) {
            CuratorTurnStore.CompletedTurn turn = byId.get(text(id));
            if (turn != null && evidence.length() <= MAX_EVIDENCE_LENGTH
                    && MemoryContentSafety.supportsExactEvidence(turn.userMessage(), evidence)) result.add(turn);
        }
        return result;
    }

    private List<FactSource> parseFactSources(Map<String, Object> fact,
                                              Map<String, CuratorTurnStore.CompletedTurn> byId,
                                              Map<String, Long> sequenceById,
                                              String value, String scope, String assertion,
                                              RejectionCounts rejectionCounts) {
        Object sourceValue = fact.get("source_turn_ids");
        Object evidenceValue = fact.get("evidence");
        if (!(sourceValue instanceof List<?> sourceIds)
                || !(evidenceValue instanceof List<?> evidenceItems)
                || sourceIds.isEmpty() || sourceIds.size() > MAX_FACT_SOURCES
                || sourceIds.size() != evidenceItems.size()) {
            rejectionCounts.add("malformed_fact_sources");
            return List.of();
        }

        List<FactSource> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < sourceIds.size(); index++) {
            rejectionCounts.source(index,text(sourceIds.get(index)),text(evidenceItems.get(index)));
            if (!(sourceIds.get(index) instanceof String) || !(evidenceItems.get(index) instanceof String)) {
                rejectionCounts.add("malformed_fact_sources");
                continue;
            }
            String turnId = text(sourceIds.get(index));
            String evidence = text(evidenceItems.get(index));
            CuratorTurnStore.CompletedTurn turn = byId.get(turnId);
            if (turnId.isBlank() || !seen.add(turnId) || turn == null
                    || evidence.isBlank() || evidence.length() > MAX_EVIDENCE_LENGTH
                    || !MemoryContentSafety.supportsExactEvidence(turn.userMessage(), evidence)) {
                rejectionCounts.add("unsupported_fact_evidence");
                continue;
            }
            if (MemoryContentSafety.looksSensitive(evidence)) {
                rejectionCounts.add("sensitive_content");
                continue;
            }
            String predicate=text(fact.get("predicate"));
            String surface=MemoryValueNormalizer.findSurface(predicate,value,evidence);
            if (fact.get("surface_values") instanceof List<?> surfaces && index<surfaces.size()) {
                String specified=text(surfaces.get(index));
                if (!specified.isBlank() && evidence.contains(specified)
                        && MemoryCuratorFactSupport.equivalentSurface(predicate,value,specified)) surface=specified;
                else surface="";
            }
            if (surface.isBlank()) {
                rejectionCounts.add("fact_value_not_in_evidence");
                continue;
            }
            if(evidence.indexOf(surface)!=evidence.lastIndexOf(surface)) {
                rejectionCounts.add("ambiguous_value_occurrence");continue;
            }
            if (!MemoryEvidenceCoverage.hasPredicateCue(evidence,predicate)) {
                String expanded=MemoryEvidenceCoverage.supportingSentence(turn.userMessage(),surface);
                if (!expanded.isBlank() && MemoryContentSafety.supportsExactEvidence(turn.userMessage(),expanded)) evidence=expanded;
            }
            rejectionCounts.source(index,turnId,evidence);
            if (!MemoryContentSafety.polarityConsistent(evidence, surface, assertion, scope)) {
                rejectionCounts.add("polarity_mismatch_or_uncertainty");
                continue;
            }
            if (!MemoryCuratorFactSupport.supports(evidence,new MemoryEvidenceCoverage.Evidence(predicate,value,scope,assertion,evidence,surface))) {
                rejectionCounts.add("predicate_evidence_insufficient"); continue;
            }
            sources.add(new FactSource(turn, evidence,surface));
        }
        sources.sort(Comparator.comparingLong(source ->
            sequenceById.getOrDefault(source.turn().turnId(), Long.MAX_VALUE)));
        return sources;
    }

    private String primarySourceTurnId(Map<String, Object> fact,
                                       Map<String, CuratorTurnStore.CompletedTurn> byId,
                                       Map<String, Long> sequenceById) {
        Object value = fact.get("source_turn_ids");
        if (!(value instanceof List<?> ids)) return "";
        String latest = "";
        long latestSequence = Long.MIN_VALUE;
        for (Object idValue : ids) {
            String id = text(idValue);
            if (!byId.containsKey(id)) continue;
            long sequence = sequenceById.getOrDefault(id, Long.MIN_VALUE);
            if (sequence > latestSequence) {
                latest = id;
                latestSequence = sequence;
            }
        }
        return latest;
    }

    private boolean isValidStringList(Object value) {
        if (!(value instanceof List<?> items)) return false;
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String)) return false;
            String id = text(item);
            if (id.isBlank() || !seen.add(id)) return false;
        }
        return true;
    }

    private boolean containsNormalized(String source, String target) {
        String normalizedSource = normalizeForComparison(source);
        String normalizedTarget = normalizeForComparison(target);
        return !normalizedTarget.isBlank() && normalizedSource.contains(normalizedTarget);
    }

    private String normalizeForComparison(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
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
            "sensitive_content", "sensitive_insight_content", "sensitive_growth_content",
            "oversized_insight_content", "oversized_growth_content",
            "invalid_replacement_unit", "compaction_action_mismatch");
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

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        List<String> ids = new ArrayList<>();
        for (Object item : items) {
            String id = text(item);
            if (!id.isBlank() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private record FactSource(CuratorTurnStore.CompletedTurn turn, String evidence,String surfaceValue) {}
    private record PreparedFact(MemoryFactService.FactCandidate candidate, List<FactSource> sources,
                                String proposedAction, List<String> replacesUnitIds, String retrievalText) {}

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
