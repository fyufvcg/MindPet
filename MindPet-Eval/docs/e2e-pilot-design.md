# E2E Memory Benchmark Infrastructure + 30-sample Pilot

> 状态：Infrastructure implemented；正式 Pilot 尚未运行。
>
> 安全边界：没有调用 DeepSeek/其他 LLM，没有写正式实验数据，没有运行 30 条请求，没有修改 production Prompt、parser、fallback、threshold、prune 或 KG/LTM 写入公式。

## 1. 实现范围

本轮新增：

- 默认关闭的 `POST /api/eval/memory/ingest`
- 受相同门禁保护的 snapshot/reset 入口
- production completed-turn 的真实 completion future
- E2E 写入观察与 partial-success 分类
- 独立数据库 bootstrap SQL
- user-scoped reset、snapshot、isolation verification 脚本
- 串行 Pilot runner 与独立幂等 replay runner
- 30 条 pending-human-annotation Pilot 模板
- Java 自动化测试

没有复制 extraction Prompt、parser、fallback、threshold 或 KG/LTM persistence 算法到 Evaluation Controller/Python。

## 2. Production 复用方式

### 2.1 实际调用链

```text
POST /api/eval/memory/ingest
  -> EvalE2eMemoryController.ingest(...)
  -> E2eMemoryEvaluationService.ingest(...)
  -> database/user guards
  -> KnowledgeGraphService.onCompletedTurnForEvaluation(...)
  -> knowledgeGraphExecutor
  -> KnowledgeGraphService.processCompletedTurn(...)
       -> extract(...)
       -> callExtractionModel(...)              [production Prompt/model]
       -> parseExtractionResponse(...)          [production parser/fallback]
       -> persist(...)                          [production KG writes]
       -> Extraction.shouldPersistMemory()      [production thresholds]
       -> PgVectorMemoryService.appendTurn(...) [production LTM/embedding/prune]
  -> CompletableFuture completes
  -> Evaluation service reads final DB state
  -> FULL_SUCCESS / KG_ONLY_PARTIAL_SUCCESS / NO_PERSIST / FAILED
```

Production `onCompletedTurn(...)` 仍然 fire-and-forget，guard、turn hash、executor、执行顺序与异常吞吐行为不变。仅把原 lambda 内已有的五行 production 处理提取为共享 private `processCompletedTurn(...)`，供 production 与 Evaluation future 同时调用。

### 2.2 Completion 正确性

- Evaluation 不使用固定 sleep。
- `onCompletedTurnForEvaluation(...)` 返回 `CompletableFuture<CompletedTurnResult>`。
- future 只在真实 executor 中的 extraction、KG persist，以及条件成立时的 `appendTurn()` 返回后完成。
- API 使用带上限的 `future.get(timeout)`；timeout 是故障边界，不是轮询正确性机制。
- future 完成后再回读数据库，因此返回时已经能观察最终 KG marker 和可追踪 LTM 行。
- runner 串行调用；上一条 HTTP 完整返回并校验后才发送下一条。

## 3. API 安全契约

### 3.1 启用条件

Controller 使用：

```text
app.eval.e2e-memory.enabled=true
```

默认或显式 false 时，Controller bean/route 不存在。

同时要求：

- `SERVER_ADDRESS=127.0.0.1`
- remote address 必须是 loopback；不信任 `X-Forwarded-For`
- `X-MindPet-Eval-Token` 必须与临时 token 常量时间匹配
- token 未配置时 fail closed
- 固定 `user_id=e2e_memory_eval_user`
- `SELECT current_database()` 必须严格等于 `mindpet_e2e_eval`

### 3.2 Ingest request

```json
{
  "sampleId": "p001",
  "runId": "pilot01",
  "userId": "e2e_memory_eval_user",
  "userMessage": "...",
  "assistantContext": "...",
  "emotion": "neutral",
  "occurredAt": "2026-10-01T00:00:00Z"
}
```

- `sampleId` 与 `runId` 必须符合 `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`。
- 服务端生成 `session_id=e2e:<runId>:<sampleId>`，并拒绝超过 production 128 字符限制的值。
- `sampleId/runId` 不进入 extraction Prompt。
- `userId` 可省略，但如果提供则只能是固定 eval user；提供其他 user 返回 403。

### 3.3 Ingest response

响应包含：

- sample/run/user/session/turn hash/model
- `turnCompleted`、`extractionCompleted`、`duplicate`
- `worthRemembering`、`shouldRemember`、importance、confidence
- parser fallback/clamp 诊断
- `ltmAttempted`、`ltmPersisted`
- evidence-linked entity/relation/evidence 变化数
- `turnIngestRecorded`
- LTM user count before/after、prune 观察值
- 五表 row ID mapping
- `errorStage`、`errorType`、message

`entityRowsCreatedOrUpdated` 统计本 turn 新 evidence 所关联的唯一 entity，不包含没有 evidence 的 synthetic User。它表示“本轮可追踪 touched rows”，不宣称都是新建行。

### 3.4 结果分类

| status | 定义 |
|---|---|
| `FULL_SUCCESS` | KG turn marker 存在；production decision 要求 LTM；本请求产生可追踪 LTM 行 |
| `KG_ONLY_PARTIAL_SUCCESS` | KG turn marker 存在且要求 LTM，但 future 完成后没有找到本请求产生的 LTM 行 |
| `NO_PERSIST` | extraction 完成但 production decision 不要求 LTM；或相同 turn 已被幂等拒绝 |
| `FAILED` | KG marker 缺失或处理抛错；Controller failure body 同时提供 stage/type |

Evaluation 只观察，不改变事务边界，也不补偿或重试 LTM。

## 4. 独立数据库

### 4.1 Bootstrap

SQL：`MindPet-Eval/sql/setup_e2e_database.sql`

它创建：

- database：`mindpet_e2e_eval`
- login role：`mindpet_e2e_runner`
- pgvector extension
- `long_term_memory` 与四张 KG 表
- production 需要的索引

密码通过 psql variable `e2e_db_password` 提供，仓库中没有默认密码。

实验账号权限：

- NOSUPERUSER / NOCREATEDB / NOCREATEROLE / NOREPLICATION
- target DB CONNECT
- public schema USAGE
- 五张表 SELECT/INSERT/UPDATE/DELETE
- sequence USAGE/SELECT
- 不授予目标 schema CREATE；schema bootstrap 由管理员执行
- 对 `mindpet` / `mindpet_eval` 不授予直接数据库权限

注意：同一 PostgreSQL cluster 的 PUBLIC CONNECT 规则不能仅靠 role-specific REVOKE 表达显式 deny；真正的强隔离还依赖表权限、API `current_database()` guard，或使用独立 PostgreSQL container/pg_hba。比赛正式环境优先使用独立容器/volume。

### 4.2 Spring 环境变量

```powershell
$env:SERVER_PORT = '8082'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/mindpet_e2e_eval'
$env:SPRING_DATASOURCE_USERNAME = 'mindpet_e2e_runner'
$env:SPRING_DATASOURCE_PASSWORD = '<temporary database password>'
$env:APP_EVAL_E2E_MEMORY_ENABLED = 'true'
$env:APP_EVAL_E2E_MEMORY_TOKEN = '<new temporary random token>'
$env:APP_EVAL_E2E_MEMORY_COMPLETION_TIMEOUT_MS = '180000'
```

LLM 与 embedding 配置必须复用并记录实际 production 配置；token/password 不写源码、配置、raw、manifest 或报告。

### 4.3 尚未物理建库

本轮没有可用的 `MINDPET_E2E_DB_PASSWORD`，因此只生成了可审计 bootstrap SQL，没有擅自生成或持久化数据库秘密，也没有物理创建数据库/账号。正式 Pilot 前必须由用户在本机提供临时密码并执行 bootstrap。

## 5. Reset、snapshot 与 isolation verification

### 5.1 Fail-closed DB 环境

Python 使用：

```text
MINDPET_E2E_DB_HOST       default 127.0.0.1
MINDPET_E2E_DB_PORT       default 5432
MINDPET_E2E_DB_NAME       must equal mindpet_e2e_eval
MINDPET_E2E_DB_USER       default mindpet_e2e_runner
MINDPET_E2E_DB_PASSWORD   required
```

连接成功后还会执行 `SELECT current_database()`；不匹配立即拒绝。

### 5.2 Reset

`reset_e2e_memory.py` 只允许固定 user，并在事务内按顺序执行带 WHERE 的 DELETE：

```text
kg_evidence -> kg_relation -> kg_entity -> kg_turn_ingest -> long_term_memory
```

禁止 TRUNCATE，不重置 sequence；随后验证五表 eval-user count 均为 0，否则事务失败。

### 5.3 Snapshot

`snapshot_e2e_memory.py` 对五表记录：

- total row count
- eval-user row count
- other-users row count
- eval-user deterministic SHA-256 digest
- other-users deterministic SHA-256 digest

digest 基于按主键排序的整行 `jsonb` 表示，包括 embedding 文本表示。

### 5.4 Verification

`verify_e2e_isolation.py` 比较 before/after 的 other-users count/digest。任何非 eval-user 变化都失败，不能生成正式指标。

## 6. Sample → DB row mapping

不修改 production schema，也不新增 `sample_id` 列。

Runner 写 `sample_mapping.jsonl`：

```text
sample_id
  -> run_id
  -> session_id = e2e:<run_id>:<sample_id>
  -> turn_hash
  -> long_term_memory IDs
  -> evidence-linked entity IDs
  -> evidence-linked relation IDs
  -> evidence IDs
```

LTM 通过固定 user + unique session + exact user message/role 回读；KG 通过 turn hash/evidence 回读。复用的 entity/relation 仍能通过本 turn evidence 追踪。

## 7. Pilot dataset

文件：`MindPet-Eval/datasets/e2e_memory/pilot_30.jsonl`

- `p001-p030`
- 六类各 5 条
- `stable_fact`
- `long_term_preference`
- `long_term_goal`
- `temporary_state`
- `one_off_information`
- `small_talk`

所有人工 Ground Truth 字段当前保持：

```json
{
  "human_should_remember": null,
  "human_importance": null,
  "expected_entities": [],
  "expected_relations": [],
  "annotation_reason": null,
  "review_status": "pending_human_annotation"
}
```

没有由 AI 自动填充后标记 confirmed。

`validate_e2e_pilot.py` 支持：

- 当前 pending-template 完整性检查
- `--require-confirmed` 正式门禁
- entity 上限 8、relation 上限 10
- production 11 entity types / 11 predicates allowlist
- relation endpoint 必须引用 `user` 或本 sample 的 `entity_key`

## 8. Serial Pilot runner

`run_e2e_pilot.py` 的固定顺序：

```text
validate confirmed ground truth
-> reset fixed eval user
-> API preflight / DB-name verification
-> snapshot before
-> 30 POST requests serially
-> wait and validate each completion
-> snapshot after
-> verify non-eval rows unchanged
-> raw output + sample mapping + manifest
```

策略：

- 每个 sample 恰好请求一次。
- fail-fast，无自动重试；部分 raw 与 FAILED manifest 保留用于诊断。
- 只有 HTTP 200 且 status 为 `FULL_SUCCESS` 或 `NO_PERSIST` 才继续。
- `KG_ONLY_PARTIAL_SUCCESS`、`FAILED`、timeout、mapping 错误立即停止。
- 不生成或计算 Pilot 正式指标。
- 当前 pending dataset 会在发送第一条请求前 fail closed。

## 9. Idempotency runner

`run_e2e_idempotency.py` 只能在 30/30 completed Pilot 后运行，默认 replay：

```text
p001, p011, p026
```

每条只 replay 一次；要求：

- HTTP 200
- `status=NO_PERSIST`
- `duplicate=true`
- entity/relation/evidence change count 全部 0
- `ltmPersisted=false`
- replay 前后五表完整 snapshot 完全相同

输出同时保存第一次 ingest response 与第二次 replay response。幂等结果不混入 30 条 Pilot 主结果。

## 10. Prune 观察

没有关闭或修改 production prune。

每次 ingest response/`sample_mapping.jsonl` 记录：

- `ltmRowsBefore`
- `ltmRowsAfter`
- `pruneOccurred`
- `pruneDeletedEstimate`

删除观察不是只比较总行数：Evaluation 会在每条请求前后读取该 eval user 的 LTM ID 集合，并以消失的 ID 数计算 `pruneDeletedEstimate`。因此“新增 1 条、同时删除 1 条、总数不变”也能被识别；专用 eval user 必须保持串行且不得有旁路写入。

30 条 Pilot 理论上不会越过 500 行门槛。600 条正式实验必须同时保存：

1. ingest-time persistence（future 完成时是否有新 LTM 行）
2. final retained state（600 条全部完成后的最终保留）

当前 production append 吞掉内部 SQL/embedding 错误，Evaluation 通过 threshold decision 与最终 row 回读差异识别 partial success，但不能得到所有底层异常细节。

## 11. 正式 Pilot 前置条件

仍需完成：

1. 人工填写并确认 30 条 Ground Truth。
2. 设置临时数据库密码，执行 `setup_e2e_database.sql`。
3. 核对低权限账号只能操作目标 DB 表。
4. 设置临时 API token、production LLM 与 BGE-M3 配置。
5. 启动 127.0.0.1:8082 实验实例并确认 `current_database()=mindpet_e2e_eval`。
6. 运行 pending/confirmed validator 与 API preflight。
7. 获得用户对发送 30 条文本给 production LLM 的明确授权。

在上述条件满足前，不运行正式 Pilot。
