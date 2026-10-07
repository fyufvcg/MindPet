package service;

import java.util.*;
import java.util.regex.Pattern;

/** Supplies missing history intent for explicitly named activities; existing ranking and budgets are unchanged. */
public final class MemoryLifecycleRetrievalBridge {
    private MemoryLifecycleRetrievalBridge() {}
    private static final Pattern ACTIVITY=Pattern.compile("第[0-9一二三四五六七八九十百]+期(?:培训|课程|活动)|(?:培训|课程|活动)(?:名称|编号)");
    public static List<String> historyQueries(String query) {
        if(query==null || !(ACTIVITY.matcher(query).find() || query.matches("(?s).*(?:培训|课程|活动|经历).*"))
                || !query.matches("(?s).*(?:完成|参加过|哪年|哪一年|地点|人数).*"))return List.of();
        // Add intent at the service boundary, without changing MemoryQueryIntent or ordinal ranking rules.
        return List.of(query+" 已完成 历史经历");
    }
}
