package config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/** Pure configuration guard: validates paths without opening or probing any database file. */
public final class EvaluationSqlitePathGuard {
    private EvaluationSqlitePathGuard() {}

    public static ValidatedPaths validate(
            String storagePath, String evaluationPath, String allowedRoot, String userHome) {
        Path storage = normalizeRequired(storagePath, "app.storage.sqlite.path");
        Path evaluation = normalizeRequired(evaluationPath, "app.eval.e2e-memory.sqlite-path");
        Path root = normalizeRequired(allowedRoot, "app.eval.e2e-memory.allowed-root");
        Path productionDefault = normalizeRequired(userHome, "user.home")
            .resolve(".mindpet").resolve("mindpet.db").normalize();

        if (!samePath(storage, evaluation)) {
            throw new GuardFailure("SQLITE_PATH_MISMATCH",
                "Configured storage path does not match the explicit evaluation SQLite path");
        }
        if (!evaluation.startsWith(root) || evaluation.equals(root)) {
            throw new GuardFailure("SQLITE_PATH_OUTSIDE_ALLOWED_ROOT",
                "Evaluation SQLite path must be a file below the allowed root");
        }
        if (samePath(evaluation, productionDefault)) {
            throw new GuardFailure("PRODUCTION_SQLITE_PATH_FORBIDDEN",
                "The default production SQLite path is forbidden for evaluation");
        }
        return new ValidatedPaths(evaluation, root);
    }

    private static Path normalizeRequired(String raw, String property) {
        if (raw == null || raw.isBlank()) {
            throw new GuardFailure("SQLITE_PATH_NOT_CONFIGURED", property + " must be configured");
        }
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new GuardFailure("SQLITE_PATH_INVALID", property + " is invalid", exception);
        }
    }

    private static boolean samePath(Path left, Path right) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return left.toString().equalsIgnoreCase(right.toString());
        }
        return left.equals(right);
    }

    public record ValidatedPaths(Path databasePath, Path allowedRoot) {}

    public static final class GuardFailure extends RuntimeException {
        private final String type;

        public GuardFailure(String type, String message) {
            this(type, message, null);
        }

        public GuardFailure(String type, String message, Throwable cause) {
            super(message, cause);
            this.type = type;
        }

        public String type() { return type; }
    }
}
