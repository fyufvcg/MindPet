package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, bounded graph retrieval. Never extracts facts or creates semantic edges. */
@Service
public class KnowledgeGraphRetrievalService {
    public record Fact(String id, String sourceId, String targetId, String source, String predicate,
                       String target, double confidence, double score, List<String> sourceTurnHashes,
                       List<String> evidenceTexts) {
        public Fact { sourceTurnHashes = List.copyOf(sourceTurnHashes); evidenceTexts = List.copyOf(evidenceTexts); }
        public String content() { return source + " --" + predicate + "--> " + target; }
    }
    private record Entity(String id, String name, String type, String summary, double importance, Timestamp seen) {}
    private record Edge(String id, String source, String target, String predicate, double confidence,
                        double importance, Timestamp seen) {}
    public record GraphRoute(Fact fact, List<String> requiredFactIds, double relevance, boolean answerCandidate) {
        public GraphRoute { requiredFactIds = List.copyOf(requiredFactIds); }
    }
    private record Walk(String entityId, List<String> entities, List<Edge> path, double seedScore) {}
    private record PathCandidate(Edge edge, List<Edge> path, double score, double relevance) {}
    private final JdbcTemplate jdbc;
    private final VectorSearchService vectors;
    private final MemoryRetrievalPolicy policy;

    public record EntityRef(String id, String name, String type) {}
    public List<EntityRef> bindingEntities(String userId) {
        return jdbc.query("SELECT id,display_name,entity_type FROM kg_entity WHERE user_id=? ORDER BY id",
            (rs, n) -> new EntityRef(rs.getString("id"), rs.getString("display_name"), rs.getString("entity_type")), userId);
    }

    public KnowledgeGraphRetrievalService(JdbcTemplate jdbc, VectorSearchService vectors, MemoryRetrievalPolicy policy) {
        this.jdbc = jdbc;
        this.vectors = vectors;
        this.policy = policy;
    }

    public List<Fact> retrieve(String userId, String query, float[] vector, int limit) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank() || limit <= 0) return List.of();
        Map<String, GraphRoute> routes = new LinkedHashMap<>();
        for (GraphRoute route : retrieveRoutes(userId, query, vector)) routes.put(route.fact().id(), route);
        Map<String, Fact> selected = new LinkedHashMap<>();
        for (GraphRoute route : routes.values()) {
            if (!route.answerCandidate()) continue;
            long additional = route.requiredFactIds().stream().filter(id -> !selected.containsKey(id)).count();
            if (selected.size() + additional > Math.min(24, limit)) continue;
            for (String id : route.requiredFactIds()) selected.putIfAbsent(id, routes.get(id).fact());
        }
        return List.copyOf(selected.values());
    }

    /** Candidate facts retain their complete path to a query seed for downstream budget selection. */
    public List<GraphRoute> retrieveRoutes(String userId, String query, float[] vector) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) return List.of();
        List<Entity> entities = jdbc.query("SELECT id,display_name,entity_type,summary,importance,last_seen "
            + "FROM kg_entity WHERE user_id=? ORDER BY id", (rs, n) -> new Entity(rs.getString("id"),
                rs.getString("display_name"), rs.getString("entity_type"), rs.getString("summary"),
                rs.getDouble("importance"), policy.readStorageTimestamp(rs.getString("last_seen"))), userId);
        Map<String, Entity> byId = new LinkedHashMap<>();
        for (Entity entity : entities) byId.put(entity.id(), entity);
        String normalized = MemoryRetrievalRanking.normalize(query);
        Map<String, Double> seeds = new LinkedHashMap<>();
        for (Entity entity : entities) {
            String name = MemoryRetrievalRanking.normalize(entity.name());
            if (name.isEmpty() || name.equals("user") || !MemoryRetrievalRanking.mentionsEntity(query, entity.name())) continue;
            // A shorter name nested only inside a longer named entity is not a second subject.
            String remaining = java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
            for (Entity other : entities) {
                String longer = MemoryRetrievalRanking.normalize(other.name());
                if (longer.length() > name.length() && longer.contains(name)) remaining = remaining.replace(longer, "");
            }
            if (MemoryRetrievalRanking.mentionsEntity(remaining, name)) seeds.put(entity.id(), 1.0);
        }
        if (seeds.isEmpty() && (normalized.startsWith("我") || normalized.contains("我的") || normalized.contains("我们"))) {
            for (Entity entity : entities) if (entity.name().equalsIgnoreCase("user")) seeds.putIfAbsent(entity.id(), 0.8);
        }
        // Exact names anchor the graph. Semantic seeds are used only when names are absent.
        if (seeds.isEmpty() && vector != null && vectors != null) {
            for (var match : vectors.search("kg_entity", userId, vector, Math.max(1, entities.size()))) {
                Entity entity = byId.get(match.id());
                if (entity != null && Double.isFinite(match.distance()) && match.distance() <= 0.45
                        && entityVisible(entity)) {
                    seeds.put(entity.id(), 1 - match.distance());
                    if (seeds.size() >= 4) break;
                }
            }
        }
        if (seeds.isEmpty()) return List.of();
        Set<String> wanted = MemoryQueryIntent.targetPredicates(query);
        List<Edge> edges = jdbc.query("SELECT id,source_entity_id,target_entity_id,predicate,confidence,importance,last_seen "
                + "FROM kg_relation WHERE user_id=? AND confidence>=0.65 AND fact_status='ACTIVE' "
                + "AND EXISTS(SELECT 1 FROM kg_evidence evidence WHERE evidence.user_id=kg_relation.user_id "
                + "AND evidence.relation_id=kg_relation.id AND TRIM(evidence.user_message)<>'') ORDER BY id",
            (rs, n) -> new Edge(rs.getString("id"), rs.getString("source_entity_id"), rs.getString("target_entity_id"),
                rs.getString("predicate"), rs.getDouble("confidence"), rs.getDouble("importance"),
                policy.readStorageTimestamp(rs.getString("last_seen"))), userId)
            .stream().filter(e -> byId.containsKey(e.source()) && byId.containsKey(e.target()) && relationVisible(e))
            .filter(e -> (seeds.containsKey(e.source()) || entityVisible(byId.get(e.source())))
                && (seeds.containsKey(e.target()) || entityVisible(byId.get(e.target())))).toList();
        Map<String, List<Edge>> adjacent = new HashMap<>();
        for (Edge edge : edges) {
            adjacent.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            adjacent.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }
        List<Walk> frontier = seeds.entrySet().stream()
            .map(seed -> new Walk(seed.getKey(), List.of(seed.getKey()), List.of(), seed.getValue())).toList();
        Map<String, PathCandidate> selected = new LinkedHashMap<>();
        Set<String> expanded = new LinkedHashSet<>();
        for (int hop = 0; hop < 4 && !frontier.isEmpty(); hop++) {
            List<Walk> next = new ArrayList<>();
            for (Walk walk : frontier) {
                String id = walk.entityId();
                if (!expanded.add(id) || expanded.size() > 80) continue;
                // Generic User and organisation hubs are useful seeds, but not unrestricted bridges.
                Entity entity = byId.get(id);
                if (hop > 0 && (entity.name().equalsIgnoreCase("user") || Set.of("organization", "tool", "technology", "preference")
                    .contains(entity.type()))) continue;
                List<Edge> neighbours = adjacent.getOrDefault(id, List.of()).stream()
                    .sorted(Comparator.<Edge>comparingDouble(e -> edgeRelevance(e, wanted, seeds)).reversed()
                        .thenComparing(Edge::id)).limit(24).toList();
                for (Edge edge : neighbours) {
                    String other = edge.source().equals(id) ? edge.target() : edge.source();
                    if (walk.entities().contains(other)) continue;
                    boolean matches = (wanted.isEmpty() || wanted.contains(edge.predicate()))
                        && targetMatchesQuestion(query, edge, byId);
                    boolean connector = Set.of("related_to", "works_on", "builds", "knows", "belongs_to").contains(edge.predicate());
                    if (hop > 0 && !matches && !connector) continue;
                    List<Edge> path = new ArrayList<>(walk.path()); path.add(edge);
                    double relevance = (matches ? 0.95 : 0.35) * walk.seedScore() - hop * 0.06;
                    double score = edgeRelevance(edge, wanted, seeds) - hop * 0.15 + (matches ? 0.2 : 0);
                    PathCandidate candidate = new PathCandidate(edge, List.copyOf(path), score, relevance);
                    PathCandidate previous = selected.get(edge.id());
                    if (previous == null || score > previous.score() || score == previous.score() && path.size() < previous.path().size()) {
                        selected.put(edge.id(), candidate);
                    }
                    if (matches || connector) {
                        List<String> visited = new ArrayList<>(walk.entities()); visited.add(other);
                        next.add(new Walk(other, List.copyOf(visited), List.copyOf(path), walk.seedScore()));
                    }
                }
            }
            frontier = next;
        }
        List<PathCandidate> ranked = selected.values().stream()
            .filter(candidate -> (wanted.isEmpty() || wanted.contains(candidate.edge().predicate()))
                && targetMatchesQuestion(query, candidate.edge(), byId))
            .sorted(Comparator.comparingDouble(PathCandidate::score)
            .reversed().thenComparing(candidate -> candidate.edge().id())).limit(24).toList();
        if (ranked.isEmpty()) return List.of();
        Map<String, Edge> needed = new LinkedHashMap<>();
        for (PathCandidate candidate : ranked) for (Edge edge : candidate.path()) needed.put(edge.id(), edge);
        Map<String, List<String>> hashes = new HashMap<>(), texts = new HashMap<>();
        // Evidence is retrieved only for selected facts and remains tied to the original relation.
        String placeholders = String.join(",", java.util.Collections.nCopies(needed.size(), "?"));
        List<Object> args = new ArrayList<>(); args.add(userId); args.addAll(needed.keySet());
        jdbc.query("SELECT relation_id,turn_hash,user_message FROM kg_evidence WHERE user_id=? AND relation_id IN ("
            + placeholders + ") AND TRIM(user_message)<>'' ORDER BY created_at DESC,id DESC", rs -> {
                String id = rs.getString("relation_id");
                List<String> evidence = texts.computeIfAbsent(id, ignored -> new ArrayList<>());
                if (evidence.size() < 2 && !evidence.contains(rs.getString("user_message"))) {
                    evidence.add(rs.getString("user_message"));
                    List<String> sources = hashes.computeIfAbsent(id, ignored -> new ArrayList<>());
                    if (!sources.contains(rs.getString("turn_hash"))) sources.add(rs.getString("turn_hash"));
                }
            }, args.toArray());
        Map<String, Fact> facts = new LinkedHashMap<>();
        for (Edge edge : needed.values()) {
            // A graph assertion without stored evidence cannot become answering context.
            if (texts.getOrDefault(edge.id(), List.of()).isEmpty()) continue;
            facts.put(edge.id(), new Fact(edge.id(), edge.source(), edge.target(), byId.get(edge.source()).name(), edge.predicate(),
                byId.get(edge.target()).name(), edge.confidence(), selected.get(edge.id()).score(),
                hashes.getOrDefault(edge.id(), List.of()), texts.get(edge.id())));
        }
        Map<String, GraphRoute> routes = new LinkedHashMap<>();
        for (PathCandidate candidate : ranked) {
            List<String> path = candidate.path().stream().map(Edge::id).toList();
            if (path.stream().allMatch(facts::containsKey)) routes.put(candidate.edge().id(),
                new GraphRoute(facts.get(candidate.edge().id()), path, Math.max(0, candidate.relevance()), true));
        }
        for (String id : needed.keySet()) if (!routes.containsKey(id) && facts.containsKey(id)) {
            PathCandidate candidate = selected.get(id);
            List<String> path = candidate.path().stream().map(Edge::id).toList();
            if (path.stream().allMatch(facts::containsKey)) routes.put(id,
                new GraphRoute(facts.get(id), path, Math.max(0, candidate.relevance()), false));
        }
        return List.copyOf(routes.values());
    }

    private double edgeRelevance(Edge edge, Set<String> wanted, Map<String, Double> seeds) {
        return (wanted.isEmpty() || wanted.contains(edge.predicate()) ? 1.0 : 0.25)
            + (seeds.containsKey(edge.source()) ? seeds.get(edge.source()) * 0.5 : 0)
            + (seeds.containsKey(edge.target()) ? seeds.get(edge.target()) * 0.4 : 0)
            + edge.confidence() * 0.05 + edge.importance() * 0.02;
    }
    private boolean targetMatchesQuestion(String query, Edge edge, Map<String, Entity> entities) {
        String text = MemoryRetrievalRanking.normalize(query);
        if (!"related_to".equals(edge.predicate())) return true;
        Entity source = entities.get(edge.source()), target = entities.get(edge.target());
        boolean event = text.contains("复盘") && (text.contains("哪次") || text.contains("哪场"));
        boolean manual = text.contains("手册") && (text.contains("哪个项目") || text.contains("什么项目")
            || text.contains("归在哪") || text.contains("属于哪"));
        // A compound question can request both targets; one target must not mask the other.
        return !event && !manual
            || event && (source.name().contains("复盘") || target.name().contains("复盘"))
            || manual && (source.name().contains("手册") || target.name().contains("手册"));
    }
    private boolean entityVisible(Entity entity) {
        if (!policy.applyRetention()) return true;
        if (entity.seen() == null) return false;
        double hours = entity.importance() >= 0.8 ? 8760 : switch (entity.type()) {
            case "person", "preference", "organization", "technology", "tool" -> 4320;
            case "project", "goal" -> 1440;
            case "event", "topic" -> 504;
            default -> 720;
        };
        return entity.importance() * Math.exp(-policy.ageHours(entity.seen()) / hours) > 0.1;
    }
    private boolean relationVisible(Edge edge) {
        return !policy.applyRetention() || edge.seen() != null && edge.importance()
            * Math.exp(-policy.ageHours(edge.seen()) / (edge.importance() >= 0.8 ? 8760 : 1440)) > 0.1;
    }

}
