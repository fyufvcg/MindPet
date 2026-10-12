package service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds retrieval facets from the current question. */
public record MemoryRetrievalQueryPlan(String query, List<Facet> facets) {
    /** One vocabulary owns both query detection and stored-evidence matching. */
    private enum Topic {
        identity("身份 职业 名字 项目 课题 客户 导师 对接", "身份 职业 名字 课题 项目名称 客户 汇报", "我叫 名字 身份 翻译 经营 研究生 项目 课题 客户 导师 对接 汇报"),
        cause("展示 原因 字号", "为何 为什么 原因 问题 看不清 失效 怎么回事 卡在 辨认 认不出 没讲清楚", "原因 因为 问题 失效 看不清 读不清 字号 后排 拥挤 密集"),
        method("讲解 结论 术语 例子", "讲法 习惯 解释 难懂 术语 复杂概念 铺垫 生词 重点 理解", "希望 习惯 解释 结论 例子 实例 案例 术语"),
        amount("预算 经费 额度 报销 票据", "预算 额度 多少元 核销 凭证 报销 经费 花费 费用 上限 多少钱 材料钱 整理钱 材料费 资料费 支出 花销 单据 用途", "预算 额度 经费 元 凭证 票据 报销 核销 用途"),
        time("日期 地点 提交 沟通", "以前 原来 之前 当前 现在 目前 最新 前后 按时间 日期 什么时候 何时 截止 延期 地点 会面 沟通点 地址 原定 延到 哪天 过去 变更", "以前 原来 之前 当前 现在 目前 改到 改成 恢复 回到 日前 日起 日期 截止 提交 活动 地点 沟通"),
        fix("展示 改进 下一版", "改进 改稿 修正 补救 怎样改 怎么改 后来怎样 下一版 新稿 调整 怎么解决 更好读", "后续 下一版 新稿 决定 减少 放大 增大 拆页 分成 疏开"),
        acceptance("验收 抽查 样本 原始来源", "验收 抽查 样本 交接门槛 合格 抽样 验过 过关 交付 核实几条 核实要 检查成果 交东西", "验收 抽查 样本 来源 出处 逐条 查源"),
        reference("引用 依据 文档版本", "引用 依据 版本 文献 出处说明 正式出处 正式材料", "引用 依据 版本 文档 说明 出处"),
        duplicate("重复 同名 日期 来源", "重复 同名 标题一样 删重 合并 名称一致 名字相同 删掉", "重复 同名 标题 删除 合并 日期 来源"),
        responsibility("分工 原件 核对 交接记录", "分工 负责 同事 哪位 谁 原件 真实性 交接登记 交接记录 校对 对接", "分工 负责 同事 原件 核验 核对 交接 登记 真实性 校对 对接 验收"),
        availability("接待 评审 空闲 排期 时间 星期 上午 下午 晚上", "何时 接待 评审 空闲 有空 休息 哪天 星期 周几 时间段 时段 上午 下午 晚上 什么时候 时间 排期 沟通时间", "接待 评审 空闲 有空 休息 星期 周一 周二 周三 周四 周五 周六 周日 上午 下午 晚上"),
        decision("选项 候选 决定 选定 未定", "选哪个 哪首 哪个方案 最终 确定 选定 决定", "候选 备选 选项 未定 没决定 未决定 未选定 没选定"),
        lifecycle("计划 后续 状态 取消 改期 完成 到期 有效期", "后来 后续 取消 改期 延期 还进行 能用 可用 到期 失效 有效期 状态 完成 最终", "计划 取消 改期 延期 未定 未决定 完成 到期 失效 有效期 截止"),
        permit("许可 授权 通行权限 有效期 到期 续期", "许可 授权 通行权限 通行权 使用权限 还能用 有效期", "许可 授权 通行权限 通行权 有效 有效期 到期 续期 含当天 包括当天"),
        historical_start("历史 起始 开始 原来 原安排", "之前 以前 原来 第一次 调整前 降频前 减少 前我 前，我 换到", "开始 起使用 起执行 日起 从 原来 之前 原安排 原数量 旧"),
        historical_change("历史 调整 更换 改为 恢复", "之前 以前 原来 第一次 调整前 降频前 减少 前我 前，我 换到", "改为 改成 改做 换到 转到 恢复 减少 降频 不再是 旧"),
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
            var namedPeople = entities.stream().filter(e -> e.type().equals("person") && !e.name().equalsIgnoreCase("User")
                && MemoryRetrievalRanking.mentionsEntity(clause, e.name())).map(KnowledgeGraphRetrievalService.EntityRef::name).toList();
            List<String> targets = !namedPeople.isEmpty() ? namedPeople : !explicit.isEmpty() ? explicit : !clauseProjects.isEmpty() ? List.copyOf(clauseProjects)
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

    /** An indirect role query can resolve several exact people; keep their time evidence separately. */
    public MemoryRetrievalQueryPlan bindAvailability(List<String> names) {
        if (names.isEmpty()) return this;
        List<Facet> result = new ArrayList<>();
        for (Facet facet : facets) {
            if (!facet.name().startsWith("availability:")) { result.add(facet); continue; }
            List<String> explicit = names.stream().filter(name -> MemoryRetrievalRanking.mentionsEntity(facet.query(), name)).toList();
            for (String name : explicit.isEmpty() ? names : explicit)
                result.add(new Facet(facet.name() + ":person:" + name, facet.query(), facet.cues(), List.of(name)));
        }
        return new MemoryRetrievalQueryPlan(query, result);
    }
}
