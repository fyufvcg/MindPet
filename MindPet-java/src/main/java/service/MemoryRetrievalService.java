package service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import util.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns production retrieval, shared ranking, serialized budget and final access reinforcement. */
@Service
public class MemoryRetrievalService {
    public record Options(boolean keyword, boolean vector, boolean graph, boolean rerank,
                          int tokenBudget, int limit, int graphLimit,
                          boolean entityBinding) {
        public Options(boolean keyword, boolean vector, boolean graph, boolean rerank, int tokens, int limit, int graphLimit) {
            this(keyword, vector, graph, rerank, tokens, limit, graphLimit, true);
        }
        public static Options defaults(int tokens) { return new Options(true, true, true, true, tokens, 40, 8); }
    }
    public record Evidence(String id, String kind, String content, String scope, String status,
                           List<String> sourceIds, double rrf, double score, List<String> requiredIds) {
        public Evidence { sourceIds = List.copyOf(sourceIds); requiredIds = List.copyOf(requiredIds); }
    }
    public record Result(List<Evidence> evidence, String context, int tokens, List<String> failedRoutes) {
        public Result { evidence = List.copyOf(evidence); failedRoutes = List.copyOf(failedRoutes); }
    }
    private static final class Candidate {
        String id, kind, content, scope, status;
        List<String> sources, required = List.of();
        List<String> graphPath = List.of();
        double rrf, relevance, preference, score;
        boolean graphCounted;
        String occurredAt;
        long sourceSequence;
        boolean temporalSource;
        boolean selectable = true;
        Set<String> graphFacts = new LinkedHashSet<>();
        double facetScore;
        Set<String> facets = new LinkedHashSet<>();
        MemoryCorpusCompactionService.RetrievalUnit unit;
    }
    private final MemoryCorpusCompactionService corpus;
    private final SqliteMemoryService raw;
    private final KnowledgeGraphRetrievalService graph;
    private final Logger logger;
    private final int defaultTokens;
    private final MemoryLifecycleCoordinator lifecycle;
    @Autowired private ConversationEvidenceSupplement conversationEvidenceSupplement;

    @Autowired
    public MemoryRetrievalService(MemoryCorpusCompactionService corpus, SqliteMemoryService raw,
            KnowledgeGraphRetrievalService graph, Logger logger,
            @Value("${app.memory.retrieval.context-token-budget:768}") int defaultTokens) {
        this.corpus = corpus;
        this.raw = raw;
        this.graph = graph;
        this.logger = logger;
        this.defaultTokens = Math.max(1, defaultTokens);
        this.lifecycle = corpus.lifecycleCoordinator();
    }
    public String getRetrievalContext(String userId, String query, float[] vector) {
        return retrieve(userId, query, vector, Options.defaults(defaultTokens), true).context();
    }

    /** Candidate generation is read-only; final selection can record access. */
    public Result retrieve(String userId, String query, float[] vector, Options options, boolean recordAccess) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank() || options.limit() <= 0 || options.tokenBudget() <= 0) {
            return new Result(List.of(), "", 0, List.of());
        }
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        MemoryRetrievalQueryPlan plan = MemoryRetrievalQueryPlan.parse(query);
        List<KnowledgeGraphRetrievalService.GraphRoute> graphRoutes = List.of();
        if (options.keyword() || options.vector()) {
            try {
                for (var route : corpus.searchUnitRoutes(userId, query, options.vector() ? vector : null, options.keyword(), options.vector())) {
                    double rrf = (options.keyword() ? MemoryRetrievalRanking.rrf(route.keywordRank()) : 0)
                        + (options.vector() ? MemoryRetrievalRanking.rrf(route.vectorRank()) : 0);
                    if (rrf == 0) continue;
                    Candidate candidate = unitCandidate(route.unit());
                    candidate.rrf = rrf;
                    candidate.relevance = MemoryRetrievalRanking.relevance(options.keyword() ? route.lexical() : 0,
                        options.vector() ? route.distance() : Double.POSITIVE_INFINITY);
                    candidate.preference = route.preference();
                    candidates.put(candidate.id, candidate);
                }
                // Migration gaps use the existing raw eligibility, never re-enter mapped inactive memories.
                for (var route : corpus.fallbackRoutes(userId, query, options.vector() ? vector : null, raw, options.keyword(), options.vector())) {
                    Candidate candidate = unitCandidate(route.unit());
                    candidate.rrf = MemoryRetrievalRanking.rrf(route.keywordRank()) + MemoryRetrievalRanking.rrf(route.vectorRank());
                    candidate.relevance = MemoryRetrievalRanking.relevance(route.lexical(), route.distance());
                    candidate.preference = route.preference();
                    candidates.putIfAbsent(candidate.id, candidate);
                }
            } catch (Exception error) { routeFailed("rag", error, failed); }
        }
        if (options.graph() && options.graphLimit() > 0 && MemoryQueryIntent.requiresGraph(query)) {
            try {
                int rank = 0;
                graphRoutes = graph.retrieveRoutes(userId, query, vector);
                for (var route : graphRoutes) {
                    var fact = route.fact();
                    int graphRank = ++rank;
                    Candidate candidate = new Candidate();
                    candidate.id = "kg:" + fact.id(); candidate.kind = "kg"; candidate.content = fact.content();
                    candidate.scope = ""; candidate.status = ""; candidate.sources = fact.sourceTurnHashes();
                    candidate.required = route.requiredFactIds().stream().map(id -> "kg:" + id).toList();
                    candidate.selectable = route.answerCandidate();
                    candidate.rrf = MemoryRetrievalRanking.rrf(graphRank);
                    candidate.relevance = route.relevance(); candidate.preference = fact.confidence();
                    candidate.graphFacts.add(candidate.id);
                    candidates.put(candidate.id, candidate);
                    // A shared source turn may be stored in both channels. Preserve the actual RAG text.
                    for (var unit : corpus.evidenceUnits(userId, query, fact.evidenceTexts())) {
                        Candidate memory = candidates.computeIfAbsent("rag:" + unit.id(), ignored -> unitCandidate(unit));
                        // Count the KG route once, even when several edges cite this same turn.
                        if (!memory.graphCounted) {
                            memory.rrf += MemoryRetrievalRanking.rrf(graphRank);
                            memory.graphCounted = true;
                        }
                        memory.relevance = Math.max(memory.relevance, route.relevance());
                        // Citation identity alone does not prove a derived unit expresses the relation.
                        if (fact.evidenceTexts().stream().filter(text -> text != null && !text.isBlank())
                                .anyMatch(unit.content()::contains)) memory.graphFacts.add(candidate.id);
                        // A graph-derived source must retain its connection to the original query entity.
                        if (memory.graphPath.isEmpty() || candidate.required.size() < memory.graphPath.size())
                            memory.graphPath = candidate.required;
                    }
                }
            } catch (Exception error) { routeFailed("kg", error, failed); }
        }
        List<KnowledgeGraphRetrievalService.EntityRef> knownEntities = List.of();
        if (options.entityBinding()) {
            try {
                knownEntities = graph.bindingEntities(userId);
                plan = plan.bind(knownEntities, graphRoutes);
            } catch (Exception error) { routeFailed("entity-binding", error, failed); }
        }
        if (options.keyword() && !plan.facets().isEmpty()) {
            try {
                // Follow a resolved relation endpoint to the semantic part of the same question.
                for (var route : corpus.searchFocusedUnitRoutes(userId, query, plan.retrievalQueries())) {
                        Candidate memory = candidates.computeIfAbsent("rag:" + route.unit().id(), ignored -> unitCandidate(route.unit()));
                        if (options.keyword()) memory.rrf = Math.max(memory.rrf, MemoryRetrievalRanking.rrf(route.keywordRank()));
                        memory.relevance = Math.max(memory.relevance, route.lexical());
                        memory.preference = Math.max(memory.preference, route.preference());
                }
            } catch (Exception error) { routeFailed("rag-facets", error, failed); }
        }
        if (options.keyword() && (MemoryQueryIntent.lifecycle(query)
                || plan.facets().stream().anyMatch(f -> f.name().startsWith("availability:")))) {
            try {
                Set<String> anchors = new LinkedHashSet<>();
                Set<String> people = new LinkedHashSet<>();
                List<KnowledgeGraphRetrievalService.GraphRoute> boundRoutes = graphRoutes;
                for (var entity : knownEntities) if (!entity.name().equalsIgnoreCase("User")
                        && (MemoryRetrievalRanking.mentionsEntity(query,entity.name())
                            || boundRoutes.stream().filter(KnowledgeGraphRetrievalService.GraphRoute::answerCandidate)
                                .anyMatch(r -> r.fact().sourceId().equals(entity.id()) || r.fact().targetId().equals(entity.id())))) {
                    if (anchors.size() < 8) anchors.add(entity.name());
                    if (entity.type().equals("person") && people.size() < 6) people.add(entity.name());
                }
                // Only already retrieved, relevant evidence can introduce an indirect person's exact name.
                if (plan.facets().stream().anyMatch(f -> f.name().startsWith("availability:"))) {
                    var relevant = candidates.values().stream().filter(c -> c.unit != null && c.relevance >= 0.4)
                        .sorted(java.util.Comparator.comparingDouble((Candidate c) -> c.relevance).reversed()).limit(6).toList();
                    for (var entity : knownEntities) if (entity.type().equals("person") && !entity.name().equalsIgnoreCase("User")
                            && relevant.stream().anyMatch(c -> MemoryRetrievalRanking.mentionsEntity(c.content,entity.name()))
                            && people.size() < 6) {
                        people.add(entity.name());
                        if (anchors.size() < 8) anchors.add(entity.name());
                    }
                }
                Set<String> followups = new LinkedHashSet<>();
                if (MemoryQueryIntent.lifecycle(query)) {
                    followups.add(query + " 取消 改期 完成 有效期");
                    for (String anchor : anchors) followups.add(anchor + " 计划 后续 取消 改期 延期 完成 到期");
                }
                if (plan.facets().stream().anyMatch(f -> f.name().startsWith("availability:"))) {
                    for (String person : people) followups.add(person + " 空闲 有空 休息 星期 上午 下午 晚上");
                    plan = plan.bindAvailability(List.copyOf(people));
                }
                for (var route : corpus.searchFocusedUnitRoutes(userId,query,List.copyOf(followups))) {
                    Candidate memory = candidates.computeIfAbsent("rag:"+route.unit().id(),ignored->unitCandidate(route.unit()));
                    memory.rrf = Math.max(memory.rrf,MemoryRetrievalRanking.rrf(route.keywordRank()));
                    memory.relevance = Math.max(memory.relevance,route.lexical());
                    memory.preference = Math.max(memory.preference,route.preference());
                }
            } catch (Exception error) { routeFailed("rag-followups",error,failed); }
        }
        if (options.keyword() && !MemoryLifecycleRetrievalBridge.historyQueries(query).isEmpty()) {
            try {
                for (String historyQuery : MemoryLifecycleRetrievalBridge.historyQueries(query))
                    for (var route : corpus.searchFocusedUnitRoutes(userId,historyQuery,List.of(query))) {
                        Candidate memory=candidates.computeIfAbsent("rag:"+route.unit().id(),ignored->unitCandidate(route.unit()));
                        memory.rrf=Math.max(memory.rrf,MemoryRetrievalRanking.rrf(route.keywordRank()));
                        memory.relevance=Math.max(memory.relevance,route.lexical());
                        memory.preference=Math.max(memory.preference,route.preference());
                    }
            } catch(Exception error) { routeFailed("lifecycle-history",error,failed); }
        }
        // Additional read-only recall shares the same selection and token budget. No memory core writes.
        if(options.keyword() && conversationEvidenceSupplement!=null) {
            try {
                List<String> supplementalQueries=new ArrayList<>(plan.retrievalQueries());
                for(var facet:plan.facets()) supplementalQueries.add(facet.topicQuery());
                // Entity follow-ups must also reach original turns if their memory admission failed.
                for (var facet : plan.facets()) if (facet.name().startsWith("availability:"))
                    for (String person : facet.entities()) supplementalQueries.add(person + " 接待 评审 星期 上午 下午 晚上");
                for(var source:conversationEvidenceSupplement.search(userId,query,supplementalQueries)) {
                    Candidate c=new Candidate();c.id=source.id();c.kind="dialogue";c.content=source.content();
                    c.scope="episodic";c.status="source";c.sources=List.of(source.turnId());
                    c.required=source.requiredIds();c.relevance=source.score();c.rrf=MemoryRetrievalRanking.rrf(1);
                    c.occurredAt=source.occurredAt();c.sourceSequence=source.sequence();c.temporalSource=source.temporal();
                    if(source.temporal())c.facets.add("source_update");
                    candidates.putIfAbsent(c.id,c);
                }
            } catch(Exception error) {routeFailed("conversation-supplement",error,failed);}
        }
        var contents = candidates.values().stream().map(c -> c.content).toList();
        // Explicit numbered subjects can make unrelated items in the same project ineligible.
        // Apply only when matching stored evidence exists; generic questions retain all routes.
        boolean focused = options.rerank() && MemoryRetrievalConstraints.focused(query)
            && candidates.values().stream().anyMatch(c -> c.unit != null
                && MemoryRetrievalConstraints.priority(query,c.content) > 0);
        for (var facet : plan.facets()) {
            var lexical = MemoryRetrievalRanking.lexicalQuery(facet.query(), contents);
            for (Candidate candidate : candidates.values()) if (candidate.unit != null || "dialogue".equals(candidate.kind)) {
                double cue = facet.cueMatch(candidate.content);
                if (facet.entityMatch(candidate.content) && cue > 0) {
                    candidate.facetScore = Math.max(candidate.facetScore, 0.65 * cue + 0.35 * lexical.score(candidate.content));
                    boolean exactTime = facet.name().startsWith("availability:") && !facet.entities().isEmpty()
                        && candidate.content.matches("(?s).*(?:周[一二三四五六日天]|星期|上午|下午|晚上|[0-9]{1,2}[:：][0-9]{2}).*");
                    boolean explicitDecision = facet.name().startsWith("decision:")
                        && candidate.content.matches("(?s).*(?:候选|备选|没决定|未决定|没选定|未选定).*");
                    boolean exactPermit=facet.name().startsWith("permit:")
                        && candidate.content.matches("(?s).*(?:许可|授权|通行权|有效期|续期).*" );
                    boolean historyDate=facet.name().startsWith("historical_start:")
                        && candidate.content.matches("(?s).*(?:从[0-9]{4}年|从[0-9]{4}-|起使用|开始|原安排).*" );
                    boolean historyChange=facet.name().startsWith("historical_change:")
                        && candidate.content.matches("(?s).*(?:改为|改成|改做|换到|转到|恢复|不再是).*" );
                    if ((cue >= 0.5 || exactTime || explicitDecision || exactPermit || historyDate || historyChange)
                            && !candidate.content.contains("[memory_reflection]")) candidate.facets.add(facet.name());
                }
            }
        }
        int routes = (options.keyword() ? 1 : 0) + (options.vector() ? 1 : 0) + (options.graph() ? 1 : 0);
        for (Candidate candidate : candidates.values()) {
            candidate.score = options.rerank()
                ? MemoryRetrievalRanking.retrievalScore(candidate.rrf, routes, candidate.relevance, candidate.preference)
                : candidate.rrf;
            if (options.rerank() && (candidate.unit != null || "dialogue".equals(candidate.kind)) && !plan.facets().isEmpty()) {
                candidate.score = 0.55 * candidate.score + 0.45 * candidate.facetScore;
            }
            if(options.rerank()&&candidate.temporalSource)candidate.score=Math.max(candidate.score,0.95);
            if (options.rerank() && candidate.unit != null) {
                double priority=MemoryRetrievalConstraints.priority(query, candidate.content);
                candidate.score += priority;
                // Derived reflections are supporting context. Prefer concise, sourced facts and raw state changes.
                if (candidate.content.contains("[memory_reflection]")) candidate.score *= 0.55;
                else if ("fact".equals(candidate.unit.unitType()) || "profile".equals(candidate.unit.unitType())) candidate.score += 0.08;
                if (MemoryQueryIntent.lifecycle(query) && ("negated".equals(candidate.unit.assertion())
                        || candidate.content.matches("(?s).*(?:取消|改期|延期|未决定|未改期|到期|失效|已完成).*"))) candidate.score += 0.12;
                if(focused && priority==0 && candidate.graphPath.isEmpty()) candidate.selectable=false;
            }
            if (candidate.unit != null) {
                LinkedHashSet<String> required = new LinkedHashSet<>(candidate.graphPath);
                required.add(candidate.id);
                candidate.required = List.copyOf(required);
            }
        }
        // The same raw turn from RAG and conversation fallback should consume the budget once.
        Set<String> rawMeanings=new LinkedHashSet<>();
        for(Candidate candidate:candidates.values()) if(candidate.unit!=null&&candidate.selectable)
            rawMeanings.add(MemoryRetrievalRanking.normalize(candidate.content));
        for(Candidate candidate:candidates.values()) if("dialogue".equals(candidate.kind)
                && !candidate.temporalSource && candidate.required.size()==1
                && rawMeanings.contains(MemoryRetrievalRanking.normalize(candidate.content)))candidate.selectable=false;
        List<MemoryRetrievalSelection.Item> items = candidates.values().stream().map(c -> new MemoryRetrievalSelection.Item(
            c.id, c.kind, c.unit == null ? "dialogue".equals(c.kind) ? MemoryRetrievalRanking.normalize(c.content) : "" : corpus.retrievalKey(userId, c.unit), c.score, c.required,
            c.graphFacts, c.selectable, c.facets)).toList();
        List<Candidate> selected = MemoryRetrievalSelection.select(items, options,
            ids -> MemoryTokenCounter.count(serialize(ids.stream().map(candidates::get).toList())))
            .stream().map(candidates::get).toList();
        List<MemoryCorpusCompactionService.RetrievalUnit> used = selected.stream()
            .filter(c -> c.unit != null).map(c -> c.unit).toList();
        if (recordAccess) {
            try { corpus.recordAccess(userId, used); }
            catch (Exception error) { routeFailed("access", error, failed); }
            try { lifecycle.recordUse(userId, used.stream().map(MemoryCorpusCompactionService.RetrievalUnit::id).toList()); }
            catch (Exception error) { routeFailed("lifecycle-access", error, failed); }
        }
        String context = serialize(selected);
        List<Evidence> evidence = selected.stream().map(c -> new Evidence(c.id, c.kind, c.content, c.scope, c.status,
            c.sources, c.rrf, c.score, c.required)).toList();
        return new Result(evidence, context, MemoryTokenCounter.count(context), failed);
    }
    private Candidate unitCandidate(MemoryCorpusCompactionService.RetrievalUnit unit) {
        Candidate candidate = new Candidate();
        candidate.id = "rag:" + unit.id(); candidate.kind = "rag"; candidate.unit = unit;
        candidate.content = unit.content(); candidate.scope = unit.scope(); candidate.status = unit.status();
        candidate.sources = unit.sourceTurnIds(); candidate.required = List.of(candidate.id);
        return candidate;
    }
    private static String serialize(java.util.Collection<Candidate> candidates) {
        if (candidates.isEmpty()) return "";
        StringBuilder context = new StringBuilder("## 相关记忆\n");
        for (Candidate candidate : candidates) {
            String label = "dialogue".equals(candidate.kind) ? "用户原文" : candidate.unit == null ? "关系事实" : "historical".equals(candidate.status) || "historical".equals(candidate.scope)
                ? "历史记忆" : "planned".equals(candidate.scope) ? "计划" : "记忆";
            context.append("- [").append(label).append("] ").append(candidate.content);
            if(candidate.occurredAt!=null)context.append("（记录时间：").append(candidate.occurredAt)
                .append("；轮次：").append(candidate.sourceSequence).append('）');
            if (candidate.unit != null) context.append(" {assertion=").append(candidate.unit.assertion())
                .append(",status=").append(candidate.unit.status()).append('}');
            if (candidate.unit != null && ((candidate.unit.validFrom() != null && !candidate.unit.validFrom().isBlank())
                    || (candidate.unit.validTo() != null && !candidate.unit.validTo().isBlank()))) {
                context.append("（有效期：");
                if (candidate.unit.validFrom() != null && !candidate.unit.validFrom().isBlank()) context.append("自 ").append(candidate.unit.validFrom()).append(" 起；");
                if (candidate.unit.validTo() != null && !candidate.unit.validTo().isBlank()) context.append("至 ").append(candidate.unit.validTo());
                context.append("）");
            }
            context.append('\n');
        }
        return context.append("逐项核对人物、职责、时间和后续状态；回响不替代直接证据。计划不证明完成，否定和到期限定必须保留。").toString();
    }
    private void routeFailed(String route, Exception error, List<String> failed) {
        failed.add(route);
        logger.log("WARN", "记忆检索通道 " + route + " 失败: " + error.getMessage());
    }
}
