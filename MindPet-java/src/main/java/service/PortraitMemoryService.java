package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import util.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the narrative "MindPet remembers" timeline from memories that were
 * selected by the memory curator or the durable-memory extraction pipeline.
 *
 * <p>This is deliberately separate from the knowledge graph. The graph exposes
 * entities and relations; this service exposes the model's remembered
 * understanding together with the conversation that supports it.</p>
 */
@Service
public class PortraitMemoryService {

    private static final int MAX_EVIDENCE_TURNS = 500;
    private static final int MAX_SOURCE_ROWS = 200;
    private static final int MAX_GALLERY_SOURCE_ROWS = 800;
    private static final long GALLERY_LINK_WINDOW_MILLIS = 15L * 60 * 1000;
    private static final DateTimeFormatter SQLITE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final Logger logger;

    public PortraitMemoryService(JdbcTemplate jdbc, Logger logger) {
        this.jdbc = jdbc;
        this.logger = logger;
    }

    public List<Map<String, Object>> listStableMemories(String userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 120));
        List<EvidenceTurn> evidenceTurns = loadEvidenceTurns(userId);
        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(loadProfiles(userId, evidenceTurns));
        candidates.addAll(loadInsights(userId, evidenceTurns));
        candidates.addAll(loadDurableEpisodes(userId, evidenceTurns));

        // Prefer the curator's distilled understanding when the same fact also
        // exists as a raw durable-memory sentence.
        candidates.sort(Comparator.comparingInt(Candidate::priority).reversed());
        List<Candidate> unique = new ArrayList<>();
        for (Candidate candidate : candidates) {
            boolean duplicate = unique.stream().anyMatch(existing ->
                equivalentMemory(existing.dedupText(), candidate.dedupText()));
            if (!duplicate) unique.add(candidate);
        }

        unique.sort(Comparator.comparingLong(Candidate::sortEpoch).reversed());
        return unique.stream().limit(limit).map(Candidate::payload).toList();
    }

    private List<Candidate> loadProfiles(String userId, List<EvidenceTurn> turns) {
        try {
            List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT slot_key,value,updated_at,confidence FROM user_profile_current "
                    + "WHERE user_id=? AND TRIM(value)<>'' ORDER BY updated_at DESC LIMIT ?",
                userId, MAX_SOURCE_ROWS);
            if (!current.isEmpty()) {
                return current.stream().map(row -> {
                    String key = empty(String.valueOf(row.getOrDefault("slot_key", "")));
                    String value = empty(String.valueOf(row.getOrDefault("value", "")));
                    String rememberedAt = empty(String.valueOf(row.getOrDefault("updated_at", "")));
                    EvidenceTurn evidence = findEvidence(turns, "", rememberedAt, List.of(value, key + " " + value));
                    String occurredAt = evidence == null ? rememberedAt : evidence.completedAt();
                    double confidence = row.get("confidence") instanceof Number n ? n.doubleValue() : 0.9;
                    Map<String, Object> item = baseItem(
                        "profile:current:" + key, "profile", "state", "当前状态", key,
                        value, occurredAt, rememberedAt, "", confidence, 1.0,
                        "memory_curator", evidence, "");
                    return new Candidate(item, value, 30, epoch(occurredAt, rememberedAt));
                }).toList();
            }
            return jdbc.query(
                "SELECT category,prop_key,prop_value,updated_at FROM user_profile "
                    + "WHERE user_id=? AND TRIM(prop_value)<>'' ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> {
                    String category = empty(rs.getString("category"));
                    String key = empty(rs.getString("prop_key"));
                    String value = empty(rs.getString("prop_value"));
                    String rememberedAt = empty(rs.getString("updated_at"));
                    EvidenceTurn evidence = findEvidence(turns, "", rememberedAt,
                        List.of(value, key + " " + value));
                    String occurredAt = evidence == null ? rememberedAt : evidence.completedAt();
                    Map<String, Object> item = baseItem(
                        "profile:" + category + ":" + key,
                        "profile", category, profileCategoryLabel(category),
                        key.isBlank() || "summary".equalsIgnoreCase(key)
                            ? "我眼中的你" : key,
                        value, occurredAt, rememberedAt, "", 0.9, 1.0,
                        "memory_curator", evidence, "");
                    return new Candidate(item, value, 30, epoch(occurredAt, rememberedAt));
                }, userId, MAX_SOURCE_ROWS);
        } catch (Exception e) {
            logger.log("WARN", "读取 MindPet 用户画像时间流失败: " + e.getMessage());
            return List.of();
        }
    }

    private List<Candidate> loadInsights(String userId, List<EvidenceTurn> turns) {
        try {
            return jdbc.query(
                "SELECT id,insight,context,created_at FROM user_insight "
                    + "WHERE user_id=? AND TRIM(insight)<>'' ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> {
                    String id = empty(rs.getString("id"));
                    String insight = empty(rs.getString("insight"));
                    String context = empty(rs.getString("context"));
                    String rememberedAt = empty(rs.getString("created_at"));
                    EvidenceTurn evidence = findEvidence(turns, "", rememberedAt,
                        context.isBlank() ? List.of(insight) : List.of(context, insight));
                    String occurredAt = evidence == null ? rememberedAt : evidence.completedAt();
                    Map<String, Object> item = baseItem(
                        "insight:" + id, "insight", "relationship", "相处与理解",
                        shortTitle(insight, 24), insight, occurredAt, rememberedAt,
                        "", 0.85, 1.0, "memory_curator", evidence, context);
                    return new Candidate(item, insight, 20, epoch(occurredAt, rememberedAt));
                }, userId, MAX_SOURCE_ROWS);
        } catch (Exception e) {
            logger.log("WARN", "读取 MindPet 用户洞察时间流失败: " + e.getMessage());
            return List.of();
        }
    }

    private List<Candidate> loadDurableEpisodes(String userId, List<EvidenceTurn> turns) {
        try {
            List<DurableMemoryRow> rows = jdbc.query(
                "SELECT id,session_id,content,importance,confidence,emotion,event_at,created_at "
                    + "FROM long_term_memory WHERE user_id=? AND role='user' "
                    + "AND COALESCE(importance,0.5)>=0.55 ORDER BY COALESCE(event_at,created_at) DESC LIMIT ?",
                (rs, rowNum) -> new DurableMemoryRow(
                    empty(rs.getString("id")), empty(rs.getString("session_id")),
                    empty(rs.getString("content")), rs.getDouble("importance"),
                    rs.getDouble("confidence"), empty(rs.getString("emotion")),
                    empty(rs.getString("event_at")), empty(rs.getString("created_at"))),
                userId, MAX_SOURCE_ROWS);

            Set<String> gallerySessions = new LinkedHashSet<>();
            for (DurableMemoryRow row : rows) {
                if (!row.sessionId().isBlank()) gallerySessions.add(row.sessionId());
            }
            Map<String, List<GallerySource>> gallerySources =
                loadGallerySources(userId, gallerySessions);

            List<Candidate> candidates = new ArrayList<>();
            for (DurableMemoryRow row : rows) {
                EvidenceTurn evidence = findEvidence(
                    turns, row.sessionId(), row.rememberedAt(), List.of(row.content()));
                String occurredAt = !row.eventAt().isBlank() ? row.eventAt()
                    : evidence == null ? row.rememberedAt() : evidence.completedAt();
                GallerySource gallerySource = findGallerySource(
                    gallerySources, row.sessionId(), row.content(), row.rememberedAt());
                Map<String, Object> item = baseItem(
                    "memory:" + row.id(), "memory", "experience", "值得记住的事",
                    shortTitle(row.content(), 24), row.content(), occurredAt, row.rememberedAt(),
                    row.emotion(), row.importance(), row.confidence(),
                    "long_term_extraction", evidence, "", row.sessionId(), gallerySource);
                candidates.add(new Candidate(
                    item, row.content(), 10, epoch(occurredAt, row.rememberedAt())));
            }
            return candidates;
        } catch (Exception e) {
            logger.log("WARN", "读取 MindPet 长期记忆时间流失败: " + e.getMessage());
            return List.of();
        }
    }

    private Map<String, List<GallerySource>> loadGallerySources(
            String userId, Set<String> sessionIds) {
        if (sessionIds.isEmpty()) return Map.of();
        List<String> sessions = sessionIds.stream()
            .filter(sessionId -> !sessionId.isBlank())
            .limit(MAX_SOURCE_ROWS)
            .toList();
        if (sessions.isEmpty()) return Map.of();

        try {
            String placeholders = "?" + ",?".repeat(sessions.size() - 1);
            List<Object> arguments = new ArrayList<>();
            arguments.add(userId);
            arguments.addAll(sessions);
            arguments.add(MAX_GALLERY_SOURCE_ROWS);
            List<GallerySource> rows = jdbc.query(
                "SELECT id,session_id,image_uri,story,created_at FROM memory_gallery "
                    + "WHERE user_id=? AND session_id IN (" + placeholders + ") "
                    + "AND TRIM(COALESCE(image_uri,''))<>'' "
                    + "ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> new GallerySource(
                    empty(rs.getString("id")), empty(rs.getString("session_id")),
                    empty(rs.getString("image_uri")), empty(rs.getString("story")),
                    empty(rs.getString("created_at"))),
                arguments.toArray());
            Map<String, List<GallerySource>> bySession = new LinkedHashMap<>();
            for (GallerySource row : rows) {
                bySession.computeIfAbsent(row.sessionId(), ignored -> new ArrayList<>()).add(row);
            }
            return bySession;
        } catch (Exception e) {
            logger.log("DEBUG", "读取记忆画廊来源失败: " + e.getMessage());
            return Map.of();
        }
    }

    private GallerySource findGallerySource(Map<String, List<GallerySource>> sources,
                                            String sessionId, String content,
                                            String rememberedAt) {
        List<GallerySource> candidates = sources.get(sessionId);
        if (candidates == null || candidates.isEmpty()) return null;

        String normalizedContent = normalize(content);
        List<GallerySource> textMatches = new ArrayList<>();
        for (GallerySource candidate : candidates) {
            String story = normalize(candidate.story());
            if (story.length() >= 2 && normalizedContent.contains(story)) {
                textMatches.add(candidate);
            }
        }
        if (textMatches.size() == 1) return textMatches.get(0);

        long memoryEpoch = epoch(rememberedAt, "");
        if (memoryEpoch <= 0) return null;
        if (textMatches.size() > 1) {
            return uniqueClosestGallerySource(textMatches, memoryEpoch, Long.MAX_VALUE);
        }

        // Image-only shares have identical conversation text. Link them only
        // when one gallery record is the sole plausible record around the
        // durable-memory write, rather than assigning an arbitrary image from
        // the shared session.
        return uniqueClosestGallerySource(
            candidates, memoryEpoch, GALLERY_LINK_WINDOW_MILLIS);
    }

    private GallerySource uniqueClosestGallerySource(List<GallerySource> candidates,
                                                     long memoryEpoch, long maxDistance) {
        GallerySource closest = null;
        long closestDistance = Long.MAX_VALUE;
        boolean tied = false;
        int plausibleCount = 0;
        for (GallerySource candidate : candidates) {
            long galleryEpoch = epoch(candidate.createdAt(), "");
            if (galleryEpoch <= 0) continue;
            long distance = Math.abs(memoryEpoch - galleryEpoch);
            if (distance > maxDistance) continue;
            plausibleCount++;
            if (distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
                tied = false;
            } else if (distance == closestDistance) {
                tied = true;
            }
        }
        if (closest == null || tied) return null;
        if (maxDistance != Long.MAX_VALUE && plausibleCount != 1) return null;
        return closest;
    }

    private Map<String, Object> baseItem(
            String id, String kind, String category, String categoryLabel,
            String title, String understanding, String occurredAt, String rememberedAt,
            String emotion, double importance, double confidence, String selectionMethod,
            EvidenceTurn evidence, String curatorContext) {
        return baseItem(id, kind, category, categoryLabel, title, understanding,
            occurredAt, rememberedAt, emotion, importance, confidence, selectionMethod,
            evidence, curatorContext, "", null);
    }

    private Map<String, Object> baseItem(
            String id, String kind, String category, String categoryLabel,
            String title, String understanding, String occurredAt, String rememberedAt,
            String emotion, double importance, double confidence, String selectionMethod,
            EvidenceTurn evidence, String curatorContext, String sourceSessionId,
            GallerySource gallerySource) {
        String sessionId = !empty(sourceSessionId).isBlank()
            ? empty(sourceSessionId) : evidence == null ? "" : evidence.sessionId();
        String channel = evidence == null
            ? inferChannel(sessionId) : normalizeChannel(evidence.source(), sessionId);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("recordType", kind);
        source.put("channel", channel);
        source.put("label", sourceLabel(channel, selectionMethod));
        source.put("sessionId", sessionId);
        if (gallerySource != null) {
            source.put("galleryItemId", gallerySource.id());
            source.put("imageUri", gallerySource.imageUri());
        }

        Map<String, Object> proof = new LinkedHashMap<>();
        // Curator context is model-authored background, not a verbatim user quote.
        // Only an exact matched turn counts as source evidence.
        proof.put("available", evidence != null);
        proof.put("context", curatorContext);
        proof.put("userMessage", evidence == null ? "" : evidence.userMessage());
        proof.put("assistantReply", evidence == null ? "" : evidence.assistantReply());
        proof.put("turnId", evidence == null ? "" : evidence.turnId());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("kind", kind);
        item.put("category", category);
        item.put("categoryLabel", categoryLabel);
        item.put("title", title);
        item.put("understanding", understanding);
        item.put("occurredAt", occurredAt);
        item.put("rememberedAt", rememberedAt);
        item.put("time", occurredAt.isBlank() ? rememberedAt : occurredAt);
        item.put("emotion", emotion.isBlank() ? "neutral" : emotion);
        item.put("importance", importance);
        item.put("confidence", confidence);
        item.put("selectionMethod", selectionMethod);
        item.put("source", source);
        item.put("evidence", proof);
        return item;
    }

    private List<EvidenceTurn> loadEvidenceTurns(String userId) {
        List<EvidenceTurn> result = new ArrayList<>();
        try {
            result.addAll(jdbc.query(
                "SELECT turn_id,session_id,source,user_message,assistant_reply,completed_at "
                    + "FROM curator_turns WHERE user_id=? ORDER BY sequence DESC LIMIT ?",
                (rs, rowNum) -> new EvidenceTurn(
                    empty(rs.getString("turn_id")), empty(rs.getString("session_id")),
                    empty(rs.getString("source")), empty(rs.getString("user_message")),
                    empty(rs.getString("assistant_reply")), empty(rs.getString("completed_at"))),
                userId, MAX_EVIDENCE_TURNS));
        } catch (Exception e) {
            logger.log("DEBUG", "读取馆长对话证据失败: " + e.getMessage());
        }

        // kg_evidence gives exact source turns for long_term_memory and also
        // backfills installations that predate curator_turns.
        try {
            result.addAll(jdbc.query(
                "SELECT turn_hash,MAX(session_id) AS session_id,MAX(user_message) AS user_message,"
                    + "MAX(assistant_message) AS assistant_message,MIN(created_at) AS created_at "
                    + "FROM kg_evidence WHERE user_id=? GROUP BY turn_hash "
                    + "ORDER BY MAX(id) DESC LIMIT ?",
                (rs, rowNum) -> new EvidenceTurn(
                    empty(rs.getString("turn_hash")), empty(rs.getString("session_id")),
                    inferChannel(rs.getString("session_id")), empty(rs.getString("user_message")),
                    empty(rs.getString("assistant_message")), empty(rs.getString("created_at"))),
                userId, MAX_EVIDENCE_TURNS));
        } catch (Exception e) {
            logger.log("DEBUG", "读取长期记忆对话证据失败: " + e.getMessage());
        }

        Set<String> seen = new HashSet<>();
        List<EvidenceTurn> unique = new ArrayList<>();
        for (EvidenceTurn turn : result) {
            String key = turn.sessionId() + "\n" + normalize(turn.userMessage());
            if (seen.add(key)) unique.add(turn);
        }
        return unique;
    }

    private EvidenceTurn findEvidence(List<EvidenceTurn> turns, String preferredSession,
                                      String rememberedAt, List<String> hints) {
        EvidenceTurn best = null;
        double bestScore = 0.0;
        long rememberedEpoch = epoch(rememberedAt, "");
        for (EvidenceTurn turn : turns) {
            long turnEpoch = epoch(turn.completedAt(), "");
            // A memory cannot be supported by a conversation that happened
            // substantially after it was saved.
            if (rememberedEpoch > 0 && turnEpoch > rememberedEpoch + 300_000L) continue;

            double score = 0.0;
            for (String hint : hints) {
                score = Math.max(score, similarity(hint, turn.userMessage()));
                score = Math.max(score, similarity(hint, turn.assistantReply()) * 0.55);
            }
            if (!preferredSession.isBlank() && preferredSession.equals(turn.sessionId())) score += 0.18;
            if (rememberedEpoch > 0 && turnEpoch > 0) {
                long age = Math.max(0, rememberedEpoch - turnEpoch);
                if (age <= 24L * 60 * 60 * 1000) score += 0.08;
                else if (age <= 14L * 24 * 60 * 60 * 1000) score += 0.03;
            }
            if (score > bestScore) {
                bestScore = score;
                best = turn;
            }
        }
        return bestScore >= 0.34 ? best : null;
    }

    private double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) return 0.0;
        if (a.equals(b)) return 1.0;
        if ((a.contains(b) || b.contains(a)) && Math.min(a.length(), b.length()) >= 2) {
            double ratio = (double) Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
            return 0.58 + Math.min(0.30, ratio * 0.30);
        }

        Set<String> aGrams = grams(a);
        Set<String> bGrams = grams(b);
        if (aGrams.isEmpty() || bGrams.isEmpty()) return 0.0;
        int shared = 0;
        for (String gram : aGrams) if (bGrams.contains(gram)) shared++;
        return (2.0 * shared) / (aGrams.size() + bGrams.size());
    }

    private Set<String> grams(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value.length() == 1) {
            result.add(value);
            return result;
        }
        for (int i = 0; i < value.length() - 1; i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private boolean equivalentMemory(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) return false;
        if (a.equals(b)) return true;
        int shorter = Math.min(a.length(), b.length());
        if (shorter < 10) return false;
        return (a.contains(b) || b.contains(a))
            && (double) shorter / Math.max(a.length(), b.length()) >= 0.72;
    }

    private long epoch(String preferred, String fallback) {
        long parsed = parseEpoch(preferred);
        return parsed > 0 ? parsed : parseEpoch(fallback);
    }

    private long parseEpoch(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException ignoredIso) {
                try {
                    // SQLite CURRENT_TIMESTAMP is UTC even though it has no zone suffix.
                    return LocalDateTime.parse(raw, SQLITE_TIME)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (RuntimeException ignoredSqlite) {
                    return 0L;
                }
            }
        }
    }

    private String profileCategoryLabel(String category) {
        return switch (category) {
            case "identity" -> "关于你";
            case "preference" -> "你的偏爱";
            case "experience" -> "你的经历";
            case "state" -> "你正在经历";
            default -> "关于你的理解";
        };
    }

    private String normalizeChannel(String source, String sessionId) {
        String inferred = inferChannel(sessionId);
        if ("gallery".equals(inferred)) return inferred;
        String normalized = empty(source).toLowerCase(Locale.ROOT);
        if (normalized.equals("wechat") || normalized.equals("qq") || normalized.equals("desktop")) {
            return normalized;
        }
        return inferred;
    }

    private static String inferChannel(String sessionId) {
        String value = empty(sessionId).toLowerCase(Locale.ROOT);
        if (value.startsWith("wechat:")) return "wechat";
        if (value.startsWith("qq:")) return "qq";
        if (value.equals("memory-gallery") || value.startsWith("memory-gallery:")) return "gallery";
        if (value.equals("memory-portrait")) return "portrait";
        return value.isBlank() ? "unknown" : "desktop";
    }

    private String sourceLabel(String channel, String selectionMethod) {
        return switch (channel) {
            case "wechat" -> "微信对话";
            case "qq" -> "QQ 对话";
            case "gallery" -> "记忆画廊";
            case "portrait" -> "MindPet 记得";
            case "desktop" -> "桌面对话";
            default -> "long_term_extraction".equals(selectionMethod) ? "长期记忆" : "记忆馆长";
        };
    }

    private String shortTitle(String text, int maxLength) {
        String clean = empty(text).replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLength) return clean;
        return clean.substring(0, maxLength) + "…";
    }

    private static String normalize(String value) {
        return empty(value).toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static String empty(String value) {
        return value == null || "null".equalsIgnoreCase(value) ? "" : value.trim();
    }

    private record EvidenceTurn(String turnId, String sessionId, String source,
                                String userMessage, String assistantReply, String completedAt) {}

    private record DurableMemoryRow(String id, String sessionId, String content,
                                    double importance, double confidence, String emotion,
                                    String eventAt, String rememberedAt) {}

    private record GallerySource(String id, String sessionId, String imageUri,
                                 String story, String createdAt) {}

    private record Candidate(Map<String, Object> payload, String dedupText,
                             int priority, long sortEpoch) {}
}
