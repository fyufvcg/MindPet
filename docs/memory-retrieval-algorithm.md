# MindPet 长期记忆检索：真实源码说明

核对日期：2026-09-17。唯一算法依据为 `MindPet-java/src/main/java/service/PgVectorMemoryService.java`，并核对 `EmbeddingService.java`、`MemoryLayer.java` 及调用方。本文没有把 README、旧文档、代码注释或期望功能当作已经实现的算法。

本轮没有修改 Java，没有执行检索或消融实验。以下公式为源码推导，不是实验结果。

本次核对的 `PgVectorMemoryService.java` SHA-256：`20787CE2FAC70F2B812861EB74AA0D11D5D756BD2BD97E68B704ED696E00FEAD`。后续实施前应对比此源码快照，而不是只对照注释。

## 1. 先说结论

当前完整检索为：

```text
query → Embedding（或调用方传入 vec）
      → vec == null：直接返回空列表，关键词也不执行
      → 向量召回：保留率过滤 → cosine distance 升序 → 最多 20 条
      → 关键词召回：保留率过滤 → importance 降序取最多 100 条
                   → Java 子串匹配过滤/排序 → 最多 20 条
      → RRF（k=60）累计两路名次
      → 按数据库 id 去重，id 为 null 才回退 content
      → 完整 rerank 分数降序
      → limit(topK)
      → 更新最终结果的 access_count / last_accessed
      → 返回 MemoryResult（并没有返回 rrfScore/finalScore）
```

**当前最终评分没有 emotion 权重，也没有直接 layer 权重。** 第 166 行注释“情感 0.15 + 重要性 0.1 + 层级 0.05”与第 248～254 行实际方法不一致。应该以后者为准。

## 2. 真实方法和调用入口

行号对应本次检查的源码快照。

| 文件 | 方法/入口 | 职责与限制 |
|---|---|---|
| `service/PgVectorMemoryService.java:140` | `search(userId, query, topK)` | 先调用 `embedService.embed(query)`，再调用完整检索重载 |
| 同文件 `:147` | `search(userId, query, vec, topK)` | 主流程；vec 为 null 即返回；两路各 20；RRF；rerank；最终截断；访问更新 |
| 同文件 `:189` | `semanticSearch(userId, query, limit)` | private；内部生成 embedding 的向量召回便捷重载 |
| 同文件 `:193` | `semanticSearch(userId, vec, limit)` | private；直接执行 pgvector SQL，异常返回空列表 |
| 同文件 `:214` | `keywordSearch(userId, query, limit)` | private；先 SQL 取 100，再 Java 匹配并截断到 limit |
| 同文件 `:234` | `matchScore(content, query)` | private；完整 query 子串和 2～4 个 UTF-16 char 子串计分 |
| 同文件 `:248` | `rerankScore(r, rrfScore)` | private；实际最终重排公式 |
| 同文件 `:257` | `touchAccessed(userId, results)` | 对最终返回项逐条 UPDATE，不是只读检索 |
| 同文件 `:269` | `contentId(r)` | 非 null id 优先，否则使用 content |
| 同文件 `:276` | `prune(userId)` | 删除保留率低且 access_count<3 的记忆；不是 search 的打分步骤 |
| 同文件 `:333` | `MemoryResult` | 返回记录；不含两路 rank、RRF 分数、finalScore、last_accessed 或 access_count |
| `service/EmbeddingService.java` | `embed(text)` | Ollama 或豆包；失败返回 null；豆包请求 dimensions=1024 |
| `service/MemoryLayer.java` | `fromImportance(importance)` | 保存时 importance≥0.6 生成 layer=2，否则 layer=3；search 不直接调用这个 enum 的衰减方法 |
| `service/AiService.java:1186` | `pgMemory.search(userId, query, vec, 3)` | 正常聊天使用完整检索，复用 query embedding，TopK=3 |
| `controller/DesktopMemoryController.java:106` | `pgMemory.search(USER_ID, query, limit)` | 记忆管理搜索；USER_ID 固定 `desktop-user`；同样触发访问更新 |

现有公开 API 无法独立选择 keyword/vector/RRF 模式，且记忆管理接口不适合用于隔离实验用户。

## 3. 两路共有的 SQL 保留率过滤

令：

- `I = importance`。
- `t_access = (NOW() - COALESCE(last_accessed, created_at))`，单位小时。
- `S_layer = 5`，当数据库 `layer == 2`；其他值为 1。

共有过滤条件：

```text
G = I × exp(-t_access / (24 × S_layer + 1))
仅保留 G > 0.1 的行
```

因此 layer=2 的时间常数为 **121 小时**，其他 layer 为 **25 小时**。这是时间常数，不是固定过期时长，也不是半衰期。给定 I>0.1，且时间为非负，过滤边界为 `t_access < (24*S_layer+1)*ln(I/0.1)`。

`RETENTION_MIN` 的真实值为 0.1。过滤使用数据库 `NOW()`，不是 Java 系统时间。SQL 的比较为严格 `>`，恰好 0.1 会被排除。

注意：两路召回都已经受到 importance、layer、last_accessed/created_at 的影响。因此复用当前召回实现得到的 Keyword Only / Vector Only / RRF，不是“从整个数据库移除全部非相关性因素”的纯基线。

## 4. 真实向量召回 SQL

`semanticSearch(userId, vec, limit)` 拼接后实际执行：

```sql
SELECT id, content, role, created_at,
       event_date, event_at, event_timezone, event_precision,
       importance, COALESCE(confidence,1.0) AS confidence,
       layer, emotion,
       embedding <=> ?::vector AS distance
FROM long_term_memory
WHERE user_id = ?
  AND importance * EXP(
      -EXTRACT(EPOCH FROM (NOW() - COALESCE(last_accessed, created_at)))
      / 3600.0
      / (CASE WHEN layer = 2 THEN 5.0 ELSE 1.0 END * 24 + 1)
  ) > ?
ORDER BY embedding <=> ?::vector
LIMIT ?
```

参数绑定顺序：

| JDBC 参数 | 真实绑定值 |
|---|---|
| 1 | `EmbeddingService.toPgVectorString(vec)` |
| 2 | `userId` |
| 3 | `RETENTION_MIN = 0.1` |
| 4 | 与参数 1 相同的向量字符串 |
| 5 | `limit`，完整 search 固定传 20 |

`<=>` 是 pgvector 余弦距离运算。升序即越近越优先。这里没有额外 distance 阈值。距离本身不直接加入最终 rerank，只有向量结果的名次进入 RRF。

SQL 异常被 private 方法捕获并返回 `List.of()`，没有向上抛出失败。vec 非 null 时，向量 SQL 失败仍可能由关键词路给出最终结果。

## 5. 真实关键词召回 SQL 和 Java 排序

`keywordSearch(userId, query, limit)` 的 SQL：

```sql
SELECT id, content, role, created_at,
       event_date, event_at, event_timezone, event_precision,
       importance, COALESCE(confidence,1.0) AS confidence,
       layer, emotion, 0.5 AS distance
FROM long_term_memory
WHERE user_id = ?
  AND importance * EXP(
      -EXTRACT(EPOCH FROM (NOW() - COALESCE(last_accessed, created_at)))
      / 3600.0
      / (CASE WHEN layer = 2 THEN 5.0 ELSE 1.0 END * 24 + 1)
  ) > ?
ORDER BY importance DESC
LIMIT 100
```

参数 1=userId，参数 2=0.1。**SQL 没有 query 参数，没有 LIKE、全文检索或 BM25。** 关键词匹配发生在 Java，且只检查 SQL 返回的最多 100 行。

真实后处理：

```java
.stream()
.filter(r -> matchScore(r.content(), query) > 0)
.sorted((a, b) -> Double.compare(
    matchScore(b.content(), query), matchScore(a.content(), query)))
.limit(limit)
.toList();
```

`matchScore`：

```java
String c = content.toLowerCase();
String q = query.toLowerCase();
if (c.contains(q)) return 0.8;
int hits = 0;
for (int len = 2; len <= 4; len++) {
    for (int i = 0; i <= q.length() - len; i++) {
        if (c.contains(q.substring(i, i + len))) hits++;
    }
}
return Math.min(0.6, hits * 0.15);
```

公式：

```text
若 lower(content) 包含完整 lower(query)：keywordScore = 0.8
否则 keywordScore = min(0.6, 0.15 × hits)
```

`hits` 按 query 每个位置生成长度 2、3、4 的子串，只要 content 包含该子串就加 1。它不是专业分词，重复子串可重复计数，空格/标点没有特殊清理。大小写转换没有显式指定 Locale；字符串切分按 Java UTF-16 char 进行。

完整 search 传 limit=20，故关键词路最多 20 条；不是 SQL 直接取最相关的 20 条。数据库内超过 100 条时，低 importance 的关键词相关项可能在 Java 匹配前就丢失。匹配分相同时 Java 稳定排序保留 SQL 次序，故 importance 会间接影响关键词并列结果；SQL importance 并列没有明确次级排序。

关键词 MemoryResult.distance=0.5 是常量占位，**不是向量距离或关键词分数**。

## 6. 真实 RRF 融合

两个召回列表从名次 1 开始。常数 `k=60`：

```text
RRF(m) = Σ [m 出现在某路召回列表] × 1 / (60 + rank_in_that_list(m))
```

Java 实现位置是 search 内部循环，不是独立公开函数：

```java
for (int i = 0; i < semanticResults.size(); i++) {
    rrfScores.merge(contentId(semanticResults.get(i)),
        1.0 / (k + i + 1), Double::sum);
}
for (int i = 0; i < keywordResults.size(); i++) {
    rrfScores.merge(contentId(keywordResults.get(i)),
        1.0 / (k + i + 1), Double::sum);
}
```

没有两路不同权重、没有额外归一化、没有直接合并 cosine distance 和 keywordScore。

单路第 1 名贡献为 1/61；两路均第 1 名为 2/61≈0.0327869。这只是公式计算，不是测得效果。

两路列表合并后最多 40 个唯一候选。去重使用 id；同 id 同时命中两路时保留先加入的向量 MemoryResult，同时 RRF 累计两路名次。不同 id 即使文本相同也不会被去重。id 为 null 时才回退 content。

## 7. 真实最终排序公式

真实 `rerankScore`：

```java
long elapsed = System.currentTimeMillis()
    - (r.createdAt() != null ? r.createdAt().getTime() : 0);
double hours = elapsed / 3600000.0;
double strength = r.importance() >= 0.6 ? 5.0 : 1.0;
double timeDecay = Math.exp(-hours / (strength * 24 + 1));
return rrfScore * 0.5 + timeDecay * 0.2 + r.importance() * 0.2
    + r.confidence() * 0.05 + (r.importance() >= 0.6 ? 0.05 : 0);
```

令 `t_created` 为 Java 当前时刻距 created_at 的小时数，`S_importance = 5 if I>=0.6 else 1`：

```text
T(m) = exp(-t_created / (24 × S_importance + 1))

Final(m) = 0.5 × RRF(m)
         + 0.2 × T(m)
         + 0.2 × importance(m)
         + 0.05 × confidence(m)
         + 0.05 × [importance(m) >= 0.6]
```

最后一项是高 importance 的阶跃奖励，不能叫“layer 分数”。即使数据库 layer 被改成 1，importance≥0.6 仍可拿到该奖励及慢时间衰减。

SQL 过滤与 Java rerank 是两套不同时间依据：

| 部分 | 时间起点 | 慢衰减条件 |
|---|---|---|
| SQL 保留率过滤 | last_accessed 优先，否则 created_at | 数据库 layer==2 |
| Java 最终重排 | created_at | importance>=0.6 |

SQL 和 Java 使用各自时钟；Java 在排序比较期间反复读取当前时间，没有冻结查询时刻。created_at 为 null 时 Java 用 Unix epoch 0；未来 created_at 可令 T>1，方法没有裁剪。保存时会 clamp importance/confidence，但检索直接读取数据库值，手工插入越界数据不会在 rerank 时重新 clamp。

RRF 加权项理论最大约 0.0163934，importance 加权项对合法数据最大 0.2；二者量纲未统一。是否影响评测表现需真实运行验证，不能据此断言 Full 更好或更差。

## 8. 每个字段到底在哪起作用

| 字段/参数 | 召回或过滤 | 最终 rerank | 其他作用 |
|---|---|---|---|
| query | 生成 vec；Java 子串匹配 | 不直接使用 | 空/非法 query 未在 search 中统一校验 |
| vec / embedding | 余弦距离与向量名次 | 经 RRF 间接影响 | vec==null 时完整 search 提前退出 |
| userId | 两路 WHERE 隔离 | 不直接使用 | UPDATE 同样限制 user_id |
| topK | 不改变各路固定 20 | 最终 `.limit(topK)` | 正常聊天传 3；负数 limit 异常被捕获 |
| importance | 两路 G 过滤；关键词前 100 池排序 | 0.2*I；时间强度条件；0.05 阶跃奖励 | 保存时决定 layer |
| confidence | SQL COALESCE(null,1.0)，没有召回门槛 | 0.05*C | 没有额外阈值 |
| layer | SQL 衰减强度 | **没有直接读取** | 不能把最终阶跃奖励等同 layer 分数 |
| emotion | SELECT 并存入结果 | **完全不参与** | 只是当前返回/管理字段 |
| created_at | SQL 兜底时间 | T 的时间起点 | Prompt 时间展示 |
| last_accessed | SQL 过滤时间起点 | **不直接参与** | 返回 TopK 后更新为 NOW() |
| access_count | **不参与召回过滤** | **不参与** | TopK 后自增；prune 删除要求<3 |
| distance | 向量路排序 | 不直接参与 | 关键词路的 0.5 只是占位 |
| event_date/event_at/timezone/precision | 无评分作用 | 无评分作用 | `toPromptLine()` 的事件时间展示 |
| role | 无评分作用 | 无评分作用 | Prompt 用户/MindPet 标签 |
| session_id | search 不按它过滤 | 不参与 | 保存追溯；非跨会话隔离条件 |
| metadata | 本次 search 未读取 | 不参与 | 表中可能存在，不是检索依据 |
| MemoryResult.retentionRate | 召回构造器固定传 1.0 | 不参与 | **不是 SQL 实际 G，也不是 T** |

## 9. 最终 TopK 和访问写入的真实位置

先对去重候选按 Final 降序排序，再 `.limit(topK).toList()`，之后才调用 `touchAccessed(userId, ranked)`。没有先按 RRF 截 TopK 再重排。

逐条执行的真实 UPDATE：

```sql
UPDATE long_term_memory
SET access_count = COALESCE(access_count,0)+1,
    last_accessed = NOW()
WHERE user_id = ? AND id = ?::bigint
```

参数为 userId 和最终候选 id。UPDATE 失败只写 DEBUG 日志，不使检索失败。

prune（不在 search 内）使用相同 G 公式，但删除条件是 `G < 0.1 AND access_count < 3`。search 严格 `>` 与 prune 严格 `<` 留下 G 恰好 0.1 的边界；高访问次数不会直接加分。append 后总量超过 500 才尝试自动 prune。

## 10. 对真实消融实验的约束

1. Python 不能复制 RRF、关键词或 rerank 算法；必须调用 Java Service。
2. 现有 search 有写副作用，四路顺序调用会修改后续保留率；实验入口必须省略 touchAccessed，不能直接拿普通聊天接口代替。
3. KEYWORD_ONLY 要独立调用现有关键词召回，不应被 vec==null 的完整检索前置条件挡住。
4. VECTOR_ONLY/RRF/FULL 的 embedding 失败必须记录 FAILED，不能当成正常零召回。
5. private 方法现在吞掉 SQL 异常；实验入口必须提供严格失败路径，否则数据库错误可能被误记为实验成功。
6. 复用现有两路 SQL 时，四种模式均保留 G 过滤；关键词仍先取 importance 前 100。RRF vs FULL 只消融后置 rerank，不消融共有保留率门槛。
7. Full 的实际消融因素是 T、importance、confidence 和 importance 阶跃奖励；本轮不应宣称研究了 emotion 重排或独立 layer 重排。
8. 并列没有明确稳定次级键：向量 SQL、关键词 SQL、去重 HashMap 及最终分数并列可能产生顺序不确定性。不要在实现框架时悄悄修改正式排序。
9. `memory_id=m001` 等数据集 ID 不能直接等同数据库 BIGSERIAL id；导入后需真实映射文件，禁止依赖文本猜测标签。
10. 数据库时间、Java 时间、created_at 和 last_accessed 都应记录；种子固定不能自动消除时钟影响。

## 11. 本轮交付边界

已交付独立 `MindPet-Eval` 骨架与 `reports/retrieval-mode-design.md` 接入设计。Java enum、Controller、debug 方法均**尚未实现**。数据 JSONL 为空，评测函数与运行脚本显式标记 NOT_IMPLEMENTED，结果目录为空，不包含任何效果或延迟数值。
