package service;

/**
 * 记忆分层 — 重要性越高，遗忘越慢。
 * 两层：IMPORTANT（重要）和 REGULAR（常规）。
 * 日常工作记忆走 WorkingMemoryStore（后续 Phase 4）。
 */
public enum MemoryLayer {
    /** 重要记忆：重要性≥0.6，慢速遗忘 */
    IMPORTANT(2, 5.0),
    /** 常规记忆：正常遗忘 */
    REGULAR(3, 1.0);

    private final int level;
    private final double strength; // 遗忘曲线 S 值

    MemoryLayer(int level, double strength) {
        this.level = level;
        this.strength = strength;
    }

    public int getLevel() { return level; }
    public double getStrength() { return strength; }

    /** 根据重要性自动分配层级 */
    public static MemoryLayer fromImportance(double importance) {
        return importance >= 0.6 ? IMPORTANT : REGULAR;
    }

    /** 计算遗忘曲线的记忆保留率：R = e^(-t / S) */
    public double retentionRate(double elapsedHours) {
        return Math.exp(-elapsedHours / (strength * 24 + 1));
    }
}
