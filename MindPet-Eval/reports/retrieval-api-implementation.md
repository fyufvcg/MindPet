# MindPet 长期记忆检索实验接口实现报告

日期：2026-09-18。项目：`D:\MindPet-dev`。分支：`feature/mindpet-evaluation`。

## 1. 交付结论与边界

已实现默认关闭的 **POST `/api/eval/memory/search`**，支持四种真实检索模式，共享现有 Java 检索 helper，不在 Python 复制算法。

开始时 Git 工作区为空，基线提交为 `5d3b83162e0a53cc885efe16a50e160098d09675`。没有切换或修改 master，没有提交本次变更。

本次只实现接口、共享 helper、模拟测试和本报告。没有启动真实业务实例，没有连接正式数据库，没有创建 `mindpet_eval`，没有安装 BGE-M3，没有修改数据库 schema，没有生成实验数据集、检索结果、CSV、图表或效果结论。

旧 `docs/memory-retrieval-algorithm.md` 和 `reports/retrieval-mode-design.md` 保留为上一阶段快照。其中“未实施”、旧行号和 GET 方案不代表本次状态；本次已实施接口以 **POST** 为准。`configs/retrieval.yaml` 及 Python 脚本仍是此前的设计骨架，本次未修改或运行。

## 2. 新增和修改文件

所有路径均以 `D:\MindPet-dev` 为根。

| 文件 | 类型 | 实际工作 |
|---|---|---|
| `MindPet-java/src/main/java/model/RetrievalMode.java` | 新增 | 四枚举及严格 wire name 解析；不把未知模式降级为 FULL |
| `MindPet-java/src/main/java/model/RetrievalDebugResult.java` | 新增 | OK 结果、真实候选字段和 FAILED 响应；可选字段显式保留 null |
| `MindPet-java/src/main/java/controller/EvalMemoryController.java` | 新增 | 条件注册 POST 接口；token、本机地址、测试用户与 JSON 类型/参数校验；明确 HTTP 错误 |
| `MindPet-java/src/main/java/service/PgVectorMemoryService.java` | 修改 | 新增只读 `searchForEvaluation`；提取严格 SQL、RRF、候选去重、FULL 排序和评分分量共享 helper |
| `MindPet-java/pom.xml` | 修改 | 仅新增 test-scope `spring-boot-starter-test`，没有升级现有生产依赖 |
| `MindPet-java/src/test/java/service/PgVectorMemoryServiceEvaluationTest.java` | 新增 | 四模式、无写操作、真实 SQL 绑定、失败路径及正式 search 回归测试 |
| `MindPet-java/src/test/java/controller/EvalMemoryControllerTest.java` | 新增 | MockMvc 接口契约、安全、错误、null 字段及最小 Spring Web 上下文条件注册测试 |
| `MindPet-Eval/reports/retrieval-api-implementation.md` | 新增 | 本报告 |

未改动 AiService、EmbeddingService、MemoryLayer、MemoryCuratorService、KnowledgeGraphService、React、Electron、应用配置和 SQL 文件。`append`、`appendTurn`、`prune`、`touchAccessed`、原构造函数与 `MemoryResult` 均未改动。

## 3. 四模式实际调用路径

入口统一为 `searchForEvaluation(userId, query, mode, topK)`，先校验固定测试用户及参数。

| wire name / enum | 实际执行路径 | 最终排序与截断 |
|---|---|---|
| `keyword_only` / KEYWORD_ONLY | 不调用 embedding；`keywordSearchStrict(...,20)` → 当前 SQL 候选池 → 当前 `matchScore` | 当前关键词分降序后先保留 20，最后 TopK |
| `vector_only` / VECTOR_ONLY | 当前 `EmbeddingService.embed` → 校验向量 → `semanticSearchStrict(...,20)` | SQL cosine distance 升序取 20，最后 TopK；不计算 `1-distance` |
| `rrf` / RRF | 当前 embedding → 向量 Top20 + 关键词 Top20 → `mergeRrf` → `mergeCandidates` | RRF 降序，最后 TopK；不执行 FULL 后置评分 |
| `mindpet_full` / FULL | 当前 embedding → 两路 Top20 → 同一 RRF/去重 → `rankFullCandidates` → 同一评分 helper | 原 FULL 分数降序，最后 TopK |

严格 SQL helper 保留原 SQL、RowMapper 和 PreparedStatement 参数；正式入口继续用原有吞异常包装，实验入口直接调用严格 helper。

### 共有召回约束没有移除

```text
G = importance × exp(-hoursSince(coalesce(last_accessed, created_at)) / (24 × S_layer + 1))
S_layer = 5 if layer == 2 else 1
仅召回 G > 0.1
```

关键词 SQL 仍按 importance 降序取最多 100 行，在 Java 中匹配、排序并截到 20。向量 SQL 仍使用 `embedding <=> ?::vector` 升序、参数 limit=20。

所以 Keyword/Vector/RRF 仍受共有时间、importance、layer 过滤影响。这里比较的是现有召回组合及后置 rerank，不是“移除所有时间和重要性因素”的纯基线。

### 共享 RRF 与 FULL 公式

```text
RRF(m) = Σ 1 / (60 + rank(m))     # rank 从 1 开始
S_importance = 5 if importance >= 0.6 else 1
T(m) = exp(-hoursSince(created_at) / (24 × S_importance + 1))
Final(m) = 0.5 × RRF(m) + 0.2 × T(m) + 0.2 × importance(m)
           + 0.05 × confidence(m) + 0.05 × [importance(m) >= 0.6]
```

RRF 的两个循环原样提取，k=60。去重仍按非 null id，否则 content；向量路先加入，同 id 保留向量记录。没有增加 BM25、emotion 权重、layer 最终权重、距离总分或新并列排序规则。

`calculateRerankComponents` 是唯一的 FULL 数学实现。正式 `rerankScore` 取它的 finalScore，实验同一排序 helper 记录它产生的分量；DTO 与 Controller 不复制公式。

## 4. 正式 search 的行为核对

没有有意改变正式算法，保留两个公开签名：

```java
search(String userId, String query, int topK)
search(String userId, String query, float[] vec, int topK)
```

保留内容：

- 便捷重载依然先调用当前 embedding，再调用带 vec 的重载。
- vec 为 null 依然直接返回空，不执行关键词 fallback。
- 两路最多 20、关键词 SQL 池 100、原 retention gate、RRF k=60、原 FULL 公式均不变。
- 仍先排序全部去重候选，再 `.limit(topK)`，不先截 RRF 的 TopK 再做 FULL。
- 仍对最终返回记录调用原 `touchAccessed`；其 SQL、逐条更新和更新失败降级不变。
- 原两路 SQL 失败依然各自降级为空；一条路失败可由另一条路返回；整体异常依然日志后返回空。
- TopK=0 仍返回空；负数触发原 limit 异常后降级为空，不给正式入口加实验 K 校验。
- Java 时钟仍在比较过程中读取，没有冻结查询时间或改变空 created_at 的 epoch 兜底。

源码差异核对和模拟回归覆盖了这些核心路径，但不声称所有真实运行场景已获零回归证明。

## 5. 实验只读保证与验证边界

实验方法只走参数校验、embedding、SELECT、Java 排序及 DTO 组装，不调用正式 search，不调用 touchAccessed、appendTurn、prune、Memory Curator 或 KnowledgeGraph，也没有 INSERT/UPDATE/DELETE/DDL。

方法标注 `@Transactional(readOnly=true)`，实际 Spring 代理调用可使用 JDBC 只读事务。它是额外约束，不取代“调用链中没有写操作”的检查，也不等同数据库权限隔离。

模拟测试在请求前清空 JDBC 调用记录，并验证四模式只有预期 SELECT，`verifyNoMoreInteractions(jdbc)` 排除 update/execute/count 等任何额外 JDBC 方法。因此请求链没有修改 access_count、last_accessed 或行数的写操作。

**尚未在真实测试库比较这三个值的前后快照。** 本次没有测试库，也未操作正式库；不能将模拟验证描述为已完成真实数据库状态验收。

原 Service 构造函数已有自动 ALTER TABLE，KnowledgeGraphService 也有建表代码，本次保留未改。只读承诺针对实验查询方法，不针对整个 Spring 应用启动。测试中的构造初始化全部发生在 mock 上，不执行实际 DDL；未来第二实例必须先确认 datasource 指向独立实验库，再启动。

## 6. API、默认关闭与安全

```text
POST /api/eval/memory/search
Content-Type: application/json
X-MindPet-Eval-Token: <本机环境变量中的令牌>
```

请求：

```json
{"query":"我喜欢什么运动？","mode":"rrf","topK":10,"userId":"eval_test_user"}
```

这只是请求格式，不是已执行的实验，也没有虚构候选响应。

Controller 使用：

```java
@ConditionalOnProperty(
    prefix = "app.eval.retrieval", name = "enabled",
    havingValue = "true", matchIfMissing = false
)
```

未配置/false 时 Bean 不存在、路由不注册；最小 Spring Web 上下文测试均确认 POST 返回 404。只有显式 true 才注册。本次没有修改 application.yml 或开启该配置。

开启后限制：

- token 由 `app.eval.retrieval.token` 读取，后备为 `APP_EVAL_RETRIEVAL_TOKEN`；不配置非空 token 则 503，错误/缺失请求 token 则 401；采用 `MessageDigest.isEqual` 比较，没有仓库内固定 token。
- 限制请求远端地址为所列 IPv4/IPv6 loopback，不以 X-Forwarded-For 放行远端请求。未来建议第二实例同时绑定 `server.address=127.0.0.1`。
- userId 只能严格等于 `eval_test_user`；Controller 和 Service 双重限制，其他用户 403。
- query 必须实际 JSON 字符串且非空白；mode 严格接受四个 wire name。
- topK 必须实际 JSON 整数且属于 1/3/5/10；不接受字符串、浮点、布尔、超范围整数或隐式类型转换。
- 非法 JSON、缺字段、空 body 或非法参数 400；错误响应不包含原始 SQL、异常 cause 或 token。

未来 Python 的 `MINDPET_EVAL_TOKEN` 应与 Java 的环境 token 相同，但本次没有实现 Python 请求适配。

### 错误契约

| 情况 | HTTP | 响应状态/code |
|---|---|---|
| 查询成功、无命中 | 200 | `status=OK, results=[]` |
| embedding 返回 null 或抛异常 | 502 | `FAILED / EMBEDDING_FAILED` |
| 向量非 1024 维、非有限值或全零 | 502 | `FAILED / INVALID_EMBEDDING` |
| JDBC/pgvector 执行失败 | 503 | `FAILED / SQL_FAILED` |
| 其他执行异常 | 500 | `FAILED / RETRIEVAL_FAILED` |
| 未配置 token | 503 | `FAILED / EVAL_TOKEN_NOT_CONFIGURED` |
| 缺失/错误 token | 401 | `FAILED / UNAUTHORIZED` |
| 非本机/其他用户 | 403 | `FAILED / LOCAL_ACCESS_ONLY` 或 `USER_NOT_ALLOWED` |
| 非法参数/JSON | 400 | `FAILED / INVALID_REQUEST` |

EmbeddingService 本身已有异常返回 null 的行为，实验层把 null 明确识别为失败；没有修改生产 embedding 行为。1024 维校验仅在实验路径，匹配当前嵌入请求约定，不加入正式 search。

## 7. Debug 字段真实语义

vectorRank、keywordRank 为各路召回列表的 1-based 名次；未执行/未命中为 null。distance 仅取真实向量路结果，没有向量命中则 null；不公开关键词占位 distance=0.5。

keywordScore 仅对真实关键词路命中计算；rrfScore 仅 RRF/FULL 返回。VectorOnly 的 rrfScore、finalScore 为 null；KeywordOnly 的 finalScore 为真实 matchScore；RRF 的 finalScore 为 RRF；FULL 为同一评分 helper 的总分。

FULL 专属字段：timeScore 是未加权 T；importanceContribution=0.2×importance；confidenceContribution=0.05×confidence；**highImportanceBonus 是已加权奖励值 0.05 或 0，而不是 0/1 指示变量**。最终分量关系为：

```text
finalScore = 0.5 × rrfScore + 0.2 × timeScore
             + importanceContribution + confidenceContribution + highImportanceBonus
```

FULL 排序记录各候选最后一次参与比较时产生的分量；单候选无排序比较时调用同一个 helper 获取分量。保留原动态时钟，不宣称所有返回分量来自统一 asOf 快照。

importance/confidence/layer/emotion/createdAt 来自当前真实召回记录；confidence 使用原 SQL COALESCE=1.0，createdAt 输出 ISO instant 字符串。emotion/layer 只是属性，不意味着存在最终贡献。没有返回假的 retentionRate、emotionContribution、layerContribution、access_count 或 last_accessed。可选字段使用显式 null，Jackson 全局 NON_NULL 配置也不会抹掉这些字段。

## 8. compile/test 实际结果

在 `D:\MindPet-dev\MindPet-java` 执行；Maven 3.9.16，实际运行 JDK Eclipse Adoptium 21.0.12.1。

| 命令 | 实际结果 | 时间（北京时间） |
|---|---|---|
| `mvn compile` | BUILD SUCCESS，退出码 0，编译 91 个生产源文件；43.786s | 2026-09-18 17:40:11 |
| 首次 `mvn test` | 56 个测试，0 failure、1 error；异常模拟重设方式错误 | 2026-09-18 17:48:25 |
| 修正测试后的 `mvn -B test` | BUILD SUCCESS，退出码 0；56 个测试，0 failure、0 error、0 skipped；8.686s | 2026-09-18 17:49:12 |

首次错误是测试用 `when(...)` 重设已会抛异常的 mock，导致设置期间抛出；改用 `doThrow(...).when(...)`，没有为此改变业务实现。

- Service：30 个测试实例。验证模式排序、RRF 公式、FULL 分量、TopK、成功空结果、embedding/SQL 失败、测试用户安全、SELECT-only、真实 SQL 绑定及正式入口的访问更新/空 vec/异常降级。
- Controller：26 个测试实例。验证 POST 契约、四 wire mode、允许 K、类型拒绝、token/用户/地址限制、错误状态、null 序列化，以及 enabled 缺失/false/true 的真实 Web 路由注册。
- 全部测试使用 mock JDBC、mock embedding、mock Service 或最小 Web 上下文，不使用 @SpringBootTest 启动真实应用、不建立数据库连接、不占用服务端口。
- 测试结果位于 `D:\MindPet-dev\MindPet-java\target\surefire-reports`；它们是测试记录，不是检索实验效果。

现有构建继承的 javac release 实际为 **17**，虽然 POM source/target 写 21；本次不改变已有构建设置。原编译的弃用/unchecked 警告，以及 Mockito 在 JDK21 下的动态 agent 警告，不是测试失败，未通过升级依赖规避。

## 9. 未来第二实例配置能力（本次未启动）

未新增第二 DataSource。后续独立实例通过标准 Spring 配置覆盖：

| 配置 | 正式实例 | 未来实验实例 |
|---|---|---|
| `server.port` | 8080 | 8081 |
| `server.address` | 保留现有值 | 建议 127.0.0.1 |
| `spring.datasource.url` | 当前 mindpet 地址 | `jdbc:postgresql://127.0.0.1:5432/mindpet_eval` |
| `spring.datasource.username/password` | 当前正式凭据 | 独立测试凭据，环境变量/本机外部配置提供 |
| `app.eval.retrieval.enabled` | 缺失或 false | true |
| `APP_EVAL_RETRIEVAL_TOKEN` | 无须配置 | 本机生成非空令牌 |

这是配置方案，不是数据库存在、服务可启动或实验已运行的证明。完整应用仍有原有配置/初始化需求；只覆盖端口和 JDBC URL 并不自动排除 Redis、其他服务、启动初始化或正式聊天接口。审核后应使用完整本机配置确认依赖与隔离，不能在正式实例上为了试接口临时开启实验。

## 10. 未完成事项与回归风险

待人工审核后另行授权：

1. 独立实验数据库/schema/pgvector 准备，确认真实 embedding 服务和向量维度一致。
2. 独立 8081 实例的配置与启动验证，确认所有初始化指向测试资源，不能误用正式库。
3. 真实测试库中核对实验查询前后 access_count、last_accessed、行数及正常 search 的访问更新。
4. Python 配置/调用器从旧方案改为实际 POST 契约、8081 和 token 环境变量，之后才安排数据导入与 ID 映射。
5. 后续授权数据生成、四组真实运行及指标汇总；本次不执行，不判断 FULL 优于 RRF。

潜在风险：共享 helper 提取仍触及正式调用链，存在模拟测试未覆盖的真实 SQL/映射/事务代理或性能回归；新增评分分量对象有少量分配开销；没有真实数据库集成证明。启用 eval 必须依赖配置隔离，代码不自动判断数据库名。只读注解不替代权限与并发控制；其他客户端写入同一用户或数据库触发器副作用也不在本次模拟保证内。

原 SQL NOW()、Java currentTimeMillis()、未固定并列键、关键词 importance 前 100 池等限制全部保留。四模式虽不更新访问状态，仍不保证任意时间重跑结果完全相同。

本轮完成后停止，等待人工审核；不继续创建数据库、安装模型、生成数据或运行消融。
