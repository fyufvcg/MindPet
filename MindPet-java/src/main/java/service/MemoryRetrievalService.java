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
        boolean selectable = true;
        Set<String> graphFacts = new LinkedHashSet<>();
        double facetScore;
        MemoryCorpusCompactionService.RetrievalUnit unit;
    }
    private final MemoryCorpusCompactionService corpus;
    private final SqliteMemoryService raw;
    private final KnowledgeGraphRetrievalService graph;
    private final Logger logger;
    private final int defaultTokens;

    @Autowired
    public MemoryRetrievalService(MemoryCorpusCompactionService corpus, SqliteMemoryService raw,
            KnowledgeGraphRetrievalService graph, Logger logger,
            @Value("${app.memory.retrieval.context-token-budget:768}") int defaultTokens) {
        this.corpus = corpus;
        this.raw = raw;
        this.graph = graph;
        this.logger = logger;
        this.defaultTokens = Math.max(1, defaultTokens);
    }
    public String getRetrievalContext(String userId, String query, float[] vector) {
        return retrieve(userId, query, vector, Options.defaults(defaultTokens), true).context();
    }

    /** Same service entry for product and experiments; candidate generation never writes access state. */
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
        if (options.entityBinding()) {
            try { plan = plan.bind(graph.bindingEntities(userId), graphRoutes); }
            catch (Exception error) { routeFailed("entity-binding", error, failed); }
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
        if (options.keyword() && (query.contains("取消") || query.contains("作业") && MemoryQueryIntent.historical(query))) {
            try {
                for (var route : corpus.searchFocusedUnitRoutes(userId,query,List.of("取消", "可取消 作业", "另一项 可取消 作业"))) {
                    Candidate memory=candidates.computeIfAbsent("rag:"+route.unit().id(),ignored->unitCandidate(route.unit()));
                    memory.rrf=Math.max(memory.rrf,MemoryRetrievalRanking.rrf(route.keywordRank()));
                    memory.relevance=Math.max(memory.relevance,route.lexical());
                }
            } catch(Exception error) { routeFailed("rag-state",error,failed); }
        }
        var contents = candidates.values().stream().filter(c -> c.unit != null).map(c -> c.content).toList();
        // Explicit numbered subjects can make unrelated items in the same project ineligible.
        // Apply only when matching stored evidence exists; generic questions retain all routes.
        boolean focused = options.rerank() && MemoryRetrievalConstraints.focused(query)
            && candidates.values().stream().anyMatch(c -> c.unit != null
                && MemoryRetrievalConstraints.priority(query,c.content) > 0);
        for (var facet : plan.facets()) {
            var lexical = MemoryRetrievalRanking.lexicalQuery(facet.query(), contents);
            for (Candidate candidate : candidates.values()) if (candidate.unit != null) {
                double cue = facet.cueMatch(candidate.content);
                if (facet.entityMatch(candidate.content) && cue > 0)
                    candidate.facetScore = Math.max(candidate.facetScore, 0.65 * cue + 0.35 * lexical.score(candidate.content));
            }
        }
        int routes = (options.keyword() ? 1 : 0) + (options.vector() ? 1 : 0) + (options.graph() ? 1 : 0);
        for (Candidate candidate : candidates.values()) {
            candidate.score = options.rerank()
                ? MemoryRetrievalRanking.retrievalScore(candidate.rrf, routes, candidate.relevance, candidate.preference)
                : candidate.rrf;
            if (options.rerank() && candidate.unit != null && !plan.facets().isEmpty()) {
                candidate.score = 0.55 * candidate.score + 0.45 * candidate.facetScore;
            }
            if (options.rerank() && candidate.unit != null) {
                double priority=MemoryRetrievalConstraints.priority(query, candidate.content);
                candidate.score += priority;
                if(focused && priority==0 && candidate.graphPath.isEmpty()) candidate.selectable=false;
            }
            if (candidate.unit != null) {
                LinkedHashSet<String> required = new LinkedHashSet<>(candidate.graphPath);
                required.add(candidate.id);
                candidate.required = List.copyOf(required);
            }
        }
        List<MemoryRetrievalSelection.Item> items = candidates.values().stream().map(c -> new MemoryRetrievalSelection.Item(
            c.id, c.kind, c.unit == null ? "" : corpus.retrievalKey(userId, c.unit), c.score, c.required,
            c.graphFacts, c.selectable)).toList();
        List<Candidate> selected = MemoryRetrievalSelection.select(items, options,
            ids -> MemoryTokenCounter.count(serialize(ids.stream().map(candidates::get).toList())))
            .stream().map(candidates::get).toList();
        List<MemoryCorpusCompactionService.RetrievalUnit> used = selected.stream()
            .filter(c -> c.unit != null).map(c -> c.unit).toList();
        if (recordAccess) {
            try { corpus.recordAccess(userId, used); }
            catch (Exception error) { routeFailed("access", error, failed); }
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
            String label = candidate.unit == null ? "关系事实" : "historical".equals(candidate.status) || "historical".equals(candidate.scope)
                ? "历史记忆" : "planned".equals(candidate.scope) ? "计划" : "记忆";
            context.append("- [").append(label).append("] ").append(candidate.content);
            if (candidate.unit != null && candidate.unit.validFrom() != null && !candidate.unit.validFrom().isBlank()) {
                context.append("（自 ").append(candidate.unit.validFrom()).append(" 起");
                if (candidate.unit.validTo() != null && !candidate.unit.validTo().isBlank()) context.append("，至 ").append(candidate.unit.validTo());
                context.append("）");
            }
            context.append('\n');
        }
        return context.append("请按问题需要参考这些记忆；区分历史、计划和已确认事实。").toString();
    }
    private void routeFailed(String route, Exception error, List<String> failed) {
        failed.add(route);
        logger.log("WARN", "记忆检索通道 " + route + " 失败: " + error.getMessage());
    }
}
