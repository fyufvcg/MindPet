package service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Query-only facets. Never consults experiment labels or expected answers. */
public record MemoryRetrievalQueryPlan(String query, List<Facet> facets) {
    /** One vocabulary owns both query detection and stored-evidence matching. */
    private enum Topic {
        cause("展示 原因 字号", "为何 为什么 原因 问题 看不清 失效 怎么回事 卡在 辨认 认不出 没讲清楚", "原因 因为 问题 失效 看不清 读不清 字号 后排 拥挤 密集"),
        method("讲解 结论 术语 例子", "讲法 习惯 解释 难懂 术语 复杂概念 铺垫 生词 重点 理解", "希望 习惯 解释 结论 例子 实例 案例 术语"),
        amount("预算 经费 额度 报销 票据", "预算 额度 多少元 核销 凭证 报销 经费 花费 费用 上限 多少钱 材料钱 整理钱 材料费 资料费 支出 花销 单据 用途", "预算 额度 经费 元 凭证 票据 报销 核销 用途"),
        time("日期 地点 提交 沟通", "以前 原来 之前 当前 现在 前后 按时间 日期 什么时候 何时 截止 延期 地点 会面 沟通点 地址 原定 延到 哪天 过去 变更", "以前 原来 之前 当前 现在 改到 改成 日前 日起 日期 截止 提交 活动 地点 沟通"),
        fix("展示 改进 下一版", "改进 改稿 修正 补救 怎样改 怎么改 后来怎样 下一版 新稿 调整 怎么解决 更好读", "后续 下一版 新稿 决定 减少 放大 增大 拆页 分成 疏开"),
        acceptance("验收 抽查 样本 原始来源", "验收 抽查 样本 交接门槛 合格 抽样 验过 过关 交付 核实几条 核实要 检查成果 交东西", "验收 抽查 样本 来源 出处 逐条 查源"),
        reference("引用 依据 文档版本", "引用 依据 版本 文献 出处说明 正式出处 正式材料", "引用 依据 版本 文档 说明 出处"),
        duplicate("重复 同名 日期 来源", "重复 同名 标题一样 删重 合并 名称一致 名字相同 删掉", "重复 同名 标题 删除 合并 日期 来源"),
        responsibility("分工 原件 核对 交接记录", "分工 原件 真实性 交接登记 交接记录", "分工 原件 核验 核对 交接 登记 真实性"),
        presentation("展示顺序 案例 关系图 功能列表", "展示顺序 先展示 先讲 功能列表 先拿 出场顺序 排序 次序 先后 先放 谁先谁后", "展示 顺序 案例 实例 关系图 功能 随后 最后");

        final String query;
        final Set<String> triggers, evidence;
        Topic(String query, String triggers, String evidence) {
            this.query = query;
            this.triggers = Set.of(triggers.split(" "));
            this.evidence = Set.of(evidence.split(" "));
        }
        boolean matches(String text) { return triggers.stream().anyMatch(text::contains); }
    }
    public record Facet(String name, String query, Set<String> cues, List<String> entities) {
        public Facet(String name, String query, Set<String> cues) { this(name, query, cues, List.of()); }
        public Facet { cues = Set.copyOf(cues); entities = List.copyOf(entities); }
        public double cueMatch(String content) {
            String text = MemoryRetrievalRanking.normalize(content);
            long matches = cues.stream().filter(text::contains).count();
            return Math.min(1, matches / 2.0);
        }
        public boolean entityMatch(String content) {
            return entities.isEmpty() || entities.stream().anyMatch(e -> MemoryRetrievalRanking.mentionsEntity(content, e));
        }
        public String focusedQuery() { return String.join(" ", entities) + " " + query; }
        public String topicQuery() {
            return String.join(" ", entities) + " " + Topic.valueOf(name.split(":")[0]).query;
        }
    }
    public MemoryRetrievalQueryPlan { facets = List.copyOf(facets); }

    public static MemoryRetrievalQueryPlan parse(String query) {
        List<Facet> facets = new ArrayList<>();
        String[] clauses = query.split("[，,；;？?。]|(?:并且|同时|另外)");
        for (int i = 0; i < clauses.length; i++) {
        String clause = clauses[i].strip();
        if (clause.isEmpty()) continue;
        String text = MemoryRetrievalRanking.normalize(clause);
        for (Topic topic : Topic.values()) if (topic.matches(text))
            facets.add(new Facet(topic.name() + ":" + i, clause, topic.evidence));
        }
        return new MemoryRetrievalQueryPlan(query, facets);
    }

    /** Bind clauses using structured KG endpoints, never by parsing serialized triple text. */
    public MemoryRetrievalQueryPlan bind(List<KnowledgeGraphRetrievalService.EntityRef> entities,
                                        List<KnowledgeGraphRetrievalService.GraphRoute> routes) {
        var projects = entities.stream().filter(e -> e.type().equals("project")).toList();
        Set<String> pathIds = new LinkedHashSet<>();
        for (var route : routes) if (route.answerCandidate()) {
            Set<String> ids = new LinkedHashSet<>();
            for (String fid : route.requiredFactIds()) for (var edge : routes) if (edge.fact().id().equals(fid)) {
                ids.add(edge.fact().sourceId()); ids.add(edge.fact().targetId());
            }
            var found = projects.stream().filter(e -> ids.contains(e.id())).map(KnowledgeGraphRetrievalService.EntityRef::id).toList();
            pathIds.addAll(found);
        }
        var global = projects.stream().filter(e -> MemoryRetrievalRanking.mentionsEntity(query, e.name()))
            .map(KnowledgeGraphRetrievalService.EntityRef::name).toList();
        var resolved = pathIds.size() == 1 ? projects.stream().filter(e -> pathIds.contains(e.id()))
            .map(KnowledgeGraphRetrievalService.EntityRef::name).toList() : List.<String>of();
        var allResolved = projects.stream().filter(e -> pathIds.contains(e.id()))
            .map(KnowledgeGraphRetrievalService.EntityRef::name).toList();
        List<Facet> bound = new ArrayList<>();
        List<String> previous = global.size() == 1 ? global : resolved;
        String[] clauses = query.split("[，,；;？?。]|(?:并且|同时|另外)");
        for (int clauseIndex = 0; clauseIndex < clauses.length; clauseIndex++) {
            String clause = clauses[clauseIndex];
            var explicit = projects.stream().filter(e -> MemoryRetrievalRanking.mentionsEntity(clause, e.name()))
                .map(KnowledgeGraphRetrievalService.EntityRef::name).toList();
            // Resolve a person mentioned in this clause through its own stored answer path.
            Set<String> clauseProjects = new LinkedHashSet<>();
            for (var route : routes) if (route.answerCandidate()) {
                Set<String> ids = new LinkedHashSet<>();
                for (String fid : route.requiredFactIds()) for (var edge : routes) if (edge.fact().id().equals(fid)) {
                    ids.add(edge.fact().sourceId()); ids.add(edge.fact().targetId());
                }
                boolean anchored = entities.stream().anyMatch(e -> e.type().equals("person") && !e.name().equalsIgnoreCase("User")
                    && ids.contains(e.id()) && MemoryRetrievalRanking.mentionsEntity(clause, e.name()));
                if (anchored) projects.stream().filter(e -> ids.contains(e.id())).forEach(e -> clauseProjects.add(e.name()));
            }
            List<String> targets = !explicit.isEmpty() ? explicit : !clauseProjects.isEmpty() ? List.copyOf(clauseProjects)
                : !previous.isEmpty() ? previous : allResolved;
            if (!explicit.isEmpty() || !clauseProjects.isEmpty()) previous = targets;
            String suffix = ":" + clauseIndex;
            for (Facet facet : facets) if (facet.name().endsWith(suffix))
                bound.add(new Facet(facet.name(), facet.query(), facet.cues(), targets));
        }
        return new MemoryRetrievalQueryPlan(query, bound);
    }
    public List<String> retrievalQueries() {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (Facet facet : facets) {
            // Once an entity is resolved, one canonical topic query avoids a second corpus scan.
            queries.add((facet.entities().isEmpty() ? facet.focusedQuery() : facet.topicQuery()).strip());
        }
        queries.remove(query);
        return List.copyOf(queries);
    }
}
