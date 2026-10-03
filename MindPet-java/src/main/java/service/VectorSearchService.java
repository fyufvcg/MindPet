package service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import util.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** SQLite vector access with sqlite-vec acceleration and an exact Java fallback. */
@Service
public class VectorSearchService {
    private static final Set<String> TABLES = Set.of(
        "long_term_memory", "user_insight", "llm_growth", "kg_entity", "memory_retrieval_unit");

    private final JdbcTemplate jdbc;
    private final Logger logger;
    private final String extensionPath;
    private volatile boolean sqliteVecAvailable;

    public VectorSearchService(JdbcTemplate jdbc, Logger logger,
        @Value("${app.storage.sqlite.vec-extension:}") String extensionPath) {
        this.jdbc = jdbc;
        this.logger = logger;
        this.extensionPath = extensionPath == null ? "" : extensionPath.trim();
    }

    @PostConstruct
    void loadExtension() {
        try {
            String version = jdbc.queryForObject("SELECT vec_version()", String.class);
            sqliteVecAvailable = version != null;
            logger.log("INFO", "sqlite-vec 已加载: " + version);
        } catch (Exception e) {
            sqliteVecAvailable = false;
            logger.log("WARN", "sqlite-vec 加载失败，使用 Java 精确余弦检索: " + e.getMessage());
        }
    }

    public boolean isSqliteVecAvailable() { return sqliteVecAvailable; }

    public List<VectorMatch> search(String table, String userId, float[] query, int limit) {
        return search(table, userId, query, limit, false, false);
    }

    public List<VectorMatch> searchMemoryUnits(String userId, float[] query, int limit,
                                               boolean includeHistorical, boolean includePlanned) {
        return search("memory_retrieval_unit", userId, query, limit, includeHistorical, includePlanned);
    }

    private List<VectorMatch> search(String table, String userId, float[] query, int limit,
                                     boolean includeHistorical, boolean includePlanned) {
        if (!TABLES.contains(table)) throw new IllegalArgumentException("Unsupported vector table: " + table);
        if (!validVector(query) || limit <= 0) return List.of();
        byte[] queryBlob = encode(query);
        String visibility = switch (table) {
            case "long_term_memory" -> " AND searchable=1";
            case "memory_retrieval_unit" -> " AND searchable=1 AND (status='active' OR (?=1 AND status='historical')) AND (?=1 OR scope<>'planned') AND (?=1 OR scope<>'historical')";
            default -> "";
        };
        if (sqliteVecAvailable) {
            try {
                return jdbc.query("SELECT id,distance FROM (SELECT CAST(id AS TEXT) AS id, vec_distance_cosine(embedding, ?) AS distance "
                        + "FROM " + table + " WHERE user_id=? AND embedding IS NOT NULL AND length(embedding)="
                        + queryBlob.length + visibility + ") WHERE distance>=0 AND distance<=2 ORDER BY distance,id LIMIT ?",
                    (rs, row) -> new VectorMatch(rs.getString("id"), rs.getDouble("distance")),
                    table.equals("memory_retrieval_unit")
                        ? new Object[] {queryBlob, userId, includeHistorical ? 1 : 0, includePlanned ? 1 : 0,
                            includeHistorical ? 1 : 0, limit}
                        : new Object[] {queryBlob, userId, limit});
            } catch (Exception e) {
                sqliteVecAvailable = false;
                logger.log("WARN", "sqlite-vec 查询失败，本次及后续查询回退 Java: " + e.getMessage());
            }
        }
        List<VectorMatch> matches = new ArrayList<>();
        jdbc.query("SELECT CAST(id AS TEXT) AS id, embedding FROM " + table
                + " WHERE user_id=? AND embedding IS NOT NULL" + visibility,
            rs -> {
                float[] candidate = decode(rs.getBytes("embedding"));
                if (candidate.length == query.length && validVector(candidate)) {
                    matches.add(new VectorMatch(rs.getString("id"), retrievalDistance(query, candidate)));
                }
            }, table.equals("memory_retrieval_unit")
                ? new Object[] {userId, includeHistorical ? 1 : 0, includePlanned ? 1 : 0,
                    includeHistorical ? 1 : 0}
                : new Object[] {userId});
        matches.sort(Comparator.comparingDouble(VectorMatch::distance).thenComparing(VectorMatch::id));
        return matches.size() <= limit ? matches : new ArrayList<>(matches.subList(0, limit));
    }

    public static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length % Float.BYTES != 0) return new float[0];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < values.length; i++) values[i] = buffer.getFloat();
        return values;
    }

    static double cosineDistance(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return 1.0;
        return 1.0 - dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** Retrieval uses finite, bounded distances without changing the graph-writing similarity helper. */
    private static double retrievalDistance(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * (double) right[i];
            leftNorm += left[i] * (double) left[i];
            rightNorm += right[i] * (double) right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return 1.0;
        return Math.max(0, Math.min(2, 1.0 - dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }

    private static boolean validVector(float[] vector) {
        if (vector == null || vector.length == 0) return false;
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) return false;
            norm += value * (double) value;
        }
        return norm > 0;
    }

    public record VectorMatch(String id, double distance) {}
}
