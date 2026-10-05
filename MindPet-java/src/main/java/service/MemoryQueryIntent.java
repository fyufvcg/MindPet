package service;

import java.util.LinkedHashSet;
import java.util.Set;

/** Deterministic retrieval routing and graph predicate hints, independent of generation. */
public final class MemoryQueryIntent {
    private MemoryQueryIntent() {}
    public static boolean historical(String query) {
        return contains(query, "以前", "之前", "曾经", "过去", "原来", "历史", "当时", "那时", "老家",
            "搬家前", "搬迁前", "原先", "曾任", "曾住", "旧址", "曾想", "打算过",
            "有效期内", "有效期限内", "有效时段内", "仍在有效期", "还在有效期", "仍在有效时段", "还在有效时段",
            "到期了吗", "已到期", "是否到期", "仍有效", "还有效", "仍然有效", "已失效", "是否失效",
            "处于有效期", "处在有效期", "有效期吗", "回到", "已完成", "结束的");
    }
    public static boolean planned(String query) {
        return contains(query, "计划", "打算", "准备", "想要", "将来", "以后", "未来", "安排", "考虑",
            "曾想", "预期", "会不会", "打算过", "下一步", "届时", "尚未", "取消", "决定");
    }
    public static boolean requiresGraph(String query) {
        return !predicates(query).isEmpty() || contains(query, "关系", "和谁", "跟谁", "认识", "朋友", "家人", "同事", "同学", "一起",
            "妈妈", "爸爸", "父母", "哥哥", "姐姐", "弟弟", "妹妹", "儿子", "女儿", "伴侣", "配偶", "联系",
            "谁", "哪个", "哪些", "哪份", "制作", "构建", "编写", "什么工具", "什么技术", "使用", "用什么", "负责", "参与", "属于", "合作",
            "计划", "安排", "约定", "参加", "偏好", "喜欢", "who", "which", "relationship", "related");
    }
    public static Set<String> predicates(String query) {
        Set<String> result = new LinkedHashSet<>();
        if (contains(query, "工具", "技术", "使用", "用什么", "采用", "依赖", "uses", "tool", "technology")) result.add("uses");
        if (contains(query, "负责", "参与", "项目", "works", "project")) result.add("works_on");
        if (contains(query, "组织", "单位", "就职", "任职", "belongs", "organization")
                || contains(query, "属于") && !contains(query, "项目", "手册", "复盘")) result.add("belongs_to");
        if (contains(query, "认识", "朋友", "联系", "家人", "妈妈", "爸爸", "伴侣", "knows", "relationship")) result.add("knows");
        if (contains(query, "计划", "约定", "安排", "未来", "打算", "准备", "plans")) result.add("plans");
        if (contains(query, "参加", "经历", "发生", "experienced")) result.add("experienced");
        boolean dislikes = contains(query, "不喜欢", "讨厌", "不偏好", "dislikes");
        if (dislikes) result.add("dislikes");
        else if (contains(query, "偏好", "喜欢", "希望", "prefers")) result.add("prefers");
        if (contains(query, "学习", "学过", "learns")) result.add("learns");
        if (contains(query, "制作", "构建", "编写", "开发", "搭建", "做的", "builds")) result.add("builds");
        if (contains(query, "关联", "相关", "关系", "对应", "有关", "手册", "文档", "资料", "related")) result.add("related_to");
        return result;
    }

    /** Terminal relations requested by the question; other predicates remain available as connectors. */
    public static Set<String> targetPredicates(String query) {
        Set<String> result = new LinkedHashSet<>();
        if (contains(query, "什么工具", "哪些工具", "哪种工具", "具体工具", "工具与技术", "工具分别", "采用什么", "使用哪些", "用什么")) result.add("uses");
        if (contains(query, "哪个组织", "什么组织", "哪家", "哪个单位", "什么单位", "归在哪", "归属")) result.add("belongs_to");
        if (contains(query, "哪次", "哪场", "哪份", "哪项资料", "哪个项目", "什么项目", "哪个事项", "什么事项", "对应什么")) {
            if (contains(query, "手册", "复盘", "资料", "活动", "文档", "索引", "关联", "对应")) result.add("related_to");
            else result.add("works_on");
        }
        if (contains(query, "制作哪", "编写哪", "构建哪", "制作什么", "编写什么", "由谁制作", "由谁编写")) result.add("builds");
        return result.isEmpty() ? predicates(query) : result;
    }

    private static boolean contains(String query, String... cues) {
        String text = MemoryRetrievalRanking.normalize(query);
        for (String cue : cues) if (text.contains(cue)) return true;
        return false;
    }
}
