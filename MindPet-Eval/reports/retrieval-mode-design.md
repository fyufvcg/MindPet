# RetrievalMode 接入方案（待审核，未实施）

日期：2026-09-17。正常业务代码未修改，实验 API 不存在。以下是设计而不是接口使用说明。

## 1. 目标与边界

让 Python 调用真实 `PgVectorMemoryService`，不复制 SQL、关键词、RRF 或完整评分到 Python。正常聊天继续走现有 search 完整模式，仍更新访问状态；实验只读查询、不写访问状态、不触发记忆写入/馆长/图谱。

Git 前置检查发现根目录及 Java 子目录均不是 Git 仓库，本轮未初始化 Git。建议后续修改 Java 前取得带历史的仓库并创建 `feature/mindpet-evaluation`；至少需要可比对的源码快照与回归基线。

## 2. 四模式的精确定义

| HTTP mode | Java enum | 复用路径 | 排序 | 前置条件 |
|---|---|---|---|---|
| keyword_only | KEYWORD_ONLY | keywordSearch(userId,query,20) | matchScore 降序，之后 TopK | PostgreSQL；不请求 embedding |
| vector_only | VECTOR_ONLY | semanticSearch(userId,vec,20) | 实际 cosine distance 升序，之后 TopK | PostgreSQL+pgvector+有效 embedding |
| rrf | RRF | 同上两路各 20，真实 RRF 融合/去重 | RRF 降序，之后 TopK，不加后置 rerank | 同上 |
| mindpet_full | FULL | 同上两路及当前 rerankScore | 当前 Final 降序，之后 TopK | 同上 |

所有模式保留相同的真实 SQL 保留率过滤；关键词还保留 importance 前 100 池。**A/B/C 不能宣称完全移除了时间、importance、layer；仅 C/D 对比明确关闭/开启后置 rerank。** 不为获得理想消融效果偷偷更改 SQL。

emotion 不参与 Full 的最终评分，layer 只作用于共有过滤；不宣传未实现因素。VectorOnly 不构造 `1-distance` 等源码没有的总分。

## 3. 建议修改哪些 Java 文件

| 文件 | 变更类型 | 计划 |
|---|---|---|
| `src/main/java/model/RetrievalMode.java` | 新增 | 四枚举、严格解析四个 wire name；未知模式报错，不能悄悄落到 Full |
| `src/main/java/model/RetrievalDebugResult.java` | 新增 | 实验响应 DTO/records；候选真实 rank/分量/缺失字段与失败诊断，不改 MemoryResult 正式契约 |
| `src/main/java/controller/EvalMemoryController.java` | 新增 | 默认关闭的只读 GET `/api/eval/memory/search`；参数验证、令牌与测试用户限制 |
| `src/main/java/service/PgVectorMemoryService.java` | 小范围修改 | 新增 searchForEvaluation；把现有内联 RRF、去重以及严格 SQL 执行提取为共享 helper，直接复用 matchScore/rerankScore；普通 search 保持原语义 |

不需要修改 AiService、EmbeddingService、MemoryLayer、Memory Curator、遗忘公式、知识图谱、React/Electron、已有 DesktopMemoryController。

后续验证需新增 Java Service/Controller 测试文件；当前 POM 没有测试依赖。批准实施时再确认是否增加 test-scope `spring-boot-starter-test`，不在本轮改 pom.xml。

## 4. 为什么需要少量共享 helper 提取

目前 semanticSearch、keywordSearch、matchScore、rerankScore 都是 private，RRF 写在普通 search 内；仅增加 Controller 不能实现四组真实消融。

拟做的变更是移动现有代码到共享 helper，不换算法：

1. 语义/关键词 SQL 各只保留一份。将实际执行放进可抛错的内部方法；原 private 入口继续按原方式 catch 并返回空，实验入口走严格失败路径。
2. RRF merge 两个循环提取为一个共享方法；普通 search 和实验 RRF/FULL 都调用它，k=60不改。
3. 原候选去重规则及实际 rerankScore 保持；debug 只能使用真实 helper 计算的分量，不复制数学表达式到 DTO/Controller。
4. 评分分量若需暴露，将 rerankScore 的现有计算提取为一份分量对象，普通方法取总分。保持原输入、公式和时钟读取方式，不能顺手修改时间衰减或并列规则。
5. 普通两个 search 签名不变：vec==null 仍提前返回；两路20；同样排序/截断；同样 touchAccessed；同样异常降级。
6. searchForEvaluation 选择模式后复用上述方法；keyword 不依赖 vec；严格校验测试用户；不调用 touchAccessed/append/prune。

默认聊天不会增加 mode 开关，也不把正式入口改成默认 Keyword/RRF。实验 debug 对象不替换正式 MemoryResult。

具体实现 diff 要在审核后展示并回归验证。“移动 helper 不换公式”仍有回归风险，不能未经测试就保证零影响。

## 5. 实验 HTTP 接口契约

拟议接口：

```text
GET /api/eval/memory/search
query=<非空文本>
mode=keyword_only|vector_only|rrf|mindpet_full
topK=1|3|5|10
userId=eval_test_user
Header X-MindPet-Eval-Token: <来自本地环境变量，不写进仓库>
```

默认关闭：Controller 使用 `@ConditionalOnProperty(prefix="app.eval.retrieval", name="enabled", havingValue="true", matchIfMissing=false)`。未启用时没有该 API（404）。启动参数可显式开启，避免改动/覆盖用户 application.yml。

开启后仍要求非空令牌、仅本机请求地址、userId 严格等于 eval_test_user；Service 同样检查测试用户，不能只依赖前端。建议 token 从 Java 环境变量 `APP_EVAL_RETRIEVAL_TOKEN` 绑定，Python 从 `MINDPET_EVAL_TOKEN` 读取同一个本地令牌。

Python config.enabled=false 为第二层防误运行，不代替后端安全控制。

请求失败明确返回 HTTP 错误及结构化 error；数据库/向量失败不能 HTTP 200 空结果冒充成功。成功无命中才可标 OK+空列表。检查 embedding非空、维度/有限值、schema/SQL异常；单纯 SELECT 1 成功不能证明召回 SQL 一定正确。

## 6. Debug 字段：哪些真实可得，哪些不能伪造

| 字段 | 可获得来源 | 缺失/限制 |
|---|---|---|
| memoryId/content | MemoryResult | 是数据库 id，不是 m001；需映射 |
| vectorRank | 真实向量列表 1-based 位置 | 未命中或 keyword_only 未执行该路为 null |
| keywordRank | 真实关键词列表 1-based 位置 | 未命中或 vector_only 未执行该路为 null |
| keywordScore | 同一 matchScore helper | 向量路单独实验未计算则 null |
| distance | 向量路 SQL 实际距离 | keyword 路占位0.5不作为真实距离公开；无实际向量命中为 null |
| rrfScore | 共享 RRF helper 的真实值 | keyword/vector 模式不计算则 null |
| importance/confidence/layer/emotion/createdAt | 真实 MemoryResult | emotion/layer 是属性，不意味存在对应最终权重 |
| timeScore | 当前 rerank 的 T | 仅 Full 且共享分量实现后返回；不能拿 retentionRate=1.0 代替 |
| importanceContribution/confidenceContribution/highImportanceBonus | 当前 rerank 同一计算所得 | 仅 Full；没有 emotionContribution/layerContribution |
| finalScore | keyword 模式取实际 matchScore；RRF取实际RRF；Full取实际Final | VectorOnly 没有现成 finalScore，使用 null并返回 distance排序说明 |
| retentionRate / SQL G | 当前 MemoryResult 是硬编码1.0 | 不是实测保留率，本轮设计不公开为真实数值 |
| access_count/last_accessed | 当前 SELECT/record 没带 | 不填假值；如后续需要，单独审核增加只读列 |

建议响应同时记录 mode、topK、status、sourceVersion、两路返回数、候选总数、开始/结束时间及分阶段 latency。Python 端记录 HTTP墙钟总耗时；Java召回/embedding/排序耗时与其区别说明。

不在本文提供虚假的候选分数 JSON。现阶段没有实际响应。

## 7. 相同数据与可重复性的控制

- 使用独立测试数据库、相同 schema、固定 eval_test_user；禁止清理或导入生产用户。
- 四组不执行 touchAccessed，否则后一次 G 会变化；实验期间不能有同一用户的 append/prune/其他客户端读写。
- 初步每个 query 各模式请求 topK=10，再以前缀计算 K=1/3/5/10，减少访问次数并保持候选上限20。
- 保存数据、标注、真实数据库 id映射、源码和依赖哈希、embedding模型、请求顺序、seed、created_at、last_accessed及运行时间。不同模型向量不能混用。
- keyword_only 不应请求 embedding；其他三路调用相同模型。是否缓存查询 embedding会改变延迟含义，未来必须同时记录嵌入与检索耗时，不能只给混合缓存耗时。
- 当前 SQL NOW() 和 Java currentTimeMillis() 随时间变动，并列顺序未冻结；第一版保留这些语义，短时运行并报告限制，不声称任意日期重跑完全相同。如果要统一 asOf/稳定并列，另行设计实验时钟/快照策略，不混入本轮微改。

## 8. 实施前和实施后的验收门槛

审核通过后依次实施，不提前运行：

1. 取得可追溯 Git/源码基线；确认本机 Maven/JDK及独立测试库可用。
2. 加 Java入口和共享 helper。回归原 search 在固定数据上的候选、RRF、评分、TopK、空vec处理和访问更新行为。
3. 测四模式排序：真实 keyword顺序、distance顺序、RRF顺序、Full顺序；核对 score分量，测试单路缺失和重复id。
4. 测实验前后 access_count/last_accessed及行数不变；正式 search 仍更新访问。
5. 测 enabled缺失/false时404，错误token/用户拒绝，非法参数拒绝，Embedding/SQL失败明确报错而不是成功空列表。
6. 完成生成器/导入映射和 Python指标单测，才跑120/40数据。
7. 任一路未准备好记 FAILED并停止正式汇总，不制造0分CSV或比较图。

## 9. 请审核的决定

推荐批准的范围：保留现有共同召回门槛，四模式只比较召回组合与后置 rerank；新增默认关闭、仅测试用户、只读实验 API；仅 PgVectorMemoryService 做共享helper提取，新增3个独立Java文件。

本轮到此停止，不实施这些 Java 修改、不操作数据库、不生成结果。
