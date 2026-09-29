# 长期记忆离线巩固与时间语义归一化方案

## 1. 结论

可以实现“即时对话与长期知识形成解耦”的模式。建议将现有记忆馆长从“直接调用保存工具”升级为“离线巩固编排器”：

1. 对话主链只负责保存原始回合和可靠的发生时间，不等待长期记忆整理。
2. 记忆馆长周期性读取一批完整回合、既有事实和用户画像，生成结构化的记忆候选与变更建议。
3. LLM 负责理解语义、识别事实、判断事实之间的关系和候选时间表达。
4. 确定性规则负责时间归一化、字段约束、冲突检查、幂等写入和画像投影。
5. 用户画像只保留每个“当前状态槽位”的最新有效值；历史事实不删除，保存在事实/事件层并标记为已过期或被替代。

这套职责划分比让 LLM 直接修改画像更可靠。LLM 可以参与理解，但不能单独决定最终的日期、字段覆盖或历史删除。

## 2. 要解决的问题

### 2.1 即时对话和长期记忆形成解耦

对话响应不应该因为记忆抽取、向量生成或冲突合并而变慢。完整回合先进入 `curator_turns`，馆长在后台按批次巩固。当前项目已经具备每 15 轮触发一次的基础，可继续保留，并增加空闲触发和失败重试。

### 2.2 当前状态和历史事实不能混用

以下内容的语义不同，不能都写入一个通用的 `location`：

| 用户表达 | 应保存的语义 | 是否更新当前画像 |
| --- | --- | --- |
| 我家在宜兴 | `home_location = 宜兴`，稳定关系 | 更新 `home_location` |
| 我现在在南京 | `current_location = 南京`，当前状态 | 更新 `current_location` |
| 我去南京了 | `current_location = 南京`，但持续时间未知 | 更新 `current_location`，不改 `home_location` |
| 我回宜兴了 | `current_location = 宜兴` | 更新 `current_location`，不删除 `home_location` |
| 我以前住在苏州 | 历史居住事实 | 不更新当前画像，写入事实层 |

画像更新的最小单位应是“语义槽位”，例如 `current_location`、`home_location`、`occupation_current`、`relationship_status_current`，而不是模糊的自然语言标签。

### 2.3 相对时间必须绑定原始回合时间

相对时间的基准是用户消息的发生时间和时区，而不是馆长实际处理时间。

例如：

- 用户在 `2026-09-22 10:00 Asia/Shanghai` 说“后天考试”；
- 正确结果是 `2026-09-24`；
- 即使馆长在 `2026-09-25` 才运行，也不能重新计算成 `2026-09-27`，更不能只保存“后天”。

因此必须同时保存原始表达和已归一化结果：原始表达用于审计，归一化结果用于检索、排序和后续推理。

## 3. 总体架构

```text
即时对话
   │
   ├─ 保存 user/assistant 完整回合 + occurred_at + timezone
   │
   ▼
离线队列 / curator_turns
   │
   ▼ 每 15 轮、空闲窗口或定时任务触发
记忆馆长批处理
   ├─ 读取最近回合
   ├─ 读取已有事实、当前画像、洞察和工作记忆
   ├─ LLM 生成结构化候选（不直接落库）
   ├─ 时间解析器：相对时间 → 绝对时间
   ├─ 事实合并器：去重、冲突、有效期、替代关系
   ├─ 画像投影器：只更新当前状态槽位
   └─ 事务提交 + 运行记录 + 工作记忆更新
```

批处理必须具备幂等性。同一批次重复执行时，不应生成重复事实、重复画像或重复洞察。

## 4. LLM 与系统规则的职责边界

### 4.1 LLM 负责的部分

LLM 适合处理需要语言和语境理解的任务：

- 从对话中识别“这是事实、计划、愿望、假设还是闲聊”；
- 抽取主体、谓词、值和语义槽位；
- 区分 `home_location` 与 `current_location`；
- 判断新陈述是在纠正、补充、替代还是描述另一件事；
- 识别“后天”“下周一”“过几天”等表达在句子中的时间角色；
- 生成候选记忆的简短规范文本和置信度；
- 发现需要用户确认的歧义。

### 4.2 记忆馆长/系统负责的部分

以下操作不能只依赖 LLM：

- 根据消息发生时间和时区计算绝对日期；
- 校验日期格式、时区、时间区间和不可能的结果；
- 决定哪个画像槽位可以被覆盖；
- 保留历史事实并设置 `superseded`、`expired` 或 `uncertain` 状态；
- 去重、唯一约束、版本号和事务提交；
- 防止把一次性陈述写成永久人格或稳定属性；
- 对低置信度或无法解析的表达标记为待确认，而不是编造精确值。

推荐的原则是：**LLM 解释，规则归一化，馆长合并，数据库提交。**

## 5. 结构化候选协议

记忆馆长不再让 LLM 直接调用 `saveUserProfile` 写入最终画像，而是要求返回候选操作。示例：

```json
{
  "facts": [
    {
      "subject": "user",
      "predicate": "current_location",
      "value": "南京",
      "scope": "current",
      "assertion": "observed",
      "confidence": 0.94,
      "source_turn_id": "turn-123",
      "time": {
        "raw": "现在在南京",
        "kind": "relative",
        "anchor": "message_occurred_at",
        "precision": "day"
      }
    },
    {
      "subject": "user",
      "predicate": "home_location",
      "value": "宜兴",
      "scope": "stable",
      "assertion": "observed",
      "confidence": 0.98,
      "source_turn_id": "turn-123",
      "time": {
        "raw": "我家在宜兴",
        "kind": "explicit",
        "precision": "unknown"
      }
    }
  ],
  "relations": [],
  "profile_operations": [
    {
      "slot": "current_location",
      "operation": "upsert",
      "fact_ref": 0
    },
    {
      "slot": "home_location",
      "operation": "upsert",
      "fact_ref": 1
    }
  ],
  "open_questions": []
}
```

协议要求每个候选带有来源回合、断言类型、作用域和置信度。`profile_operations` 只表达建议，最终是否写入由系统策略决定。

## 6. 时间语义归一化

### 6.1 时间字段

每个完整回合至少保存：

| 字段 | 含义 |
| --- | --- |
| `occurred_at` | 用户消息或完整回合发生的绝对时间，UTC 或带时区偏移 |
| `event_timezone` | 解析相对时间使用的 IANA 时区，例如 `Asia/Shanghai` |
| `raw_time_expression` | 原始文本，例如“后天” |
| `normalized_start` | 归一化后的开始时间 |
| `normalized_end` | 归一化后的结束时间，可为空 |
| `time_precision` | `instant`、`hour`、`day`、`week`、`month`、`unknown` |
| `time_status` | `resolved`、`ambiguous`、`unresolved`、`invalid` |
| `time_anchor` | `message_occurred_at`、`explicit_date`、`conversation_context` |

现有 `long_term_memory` 已有 `event_date`、`event_at`、`event_timezone`、`event_precision`，应补上原始表达和解析状态；画像槽位也应保存有效期，而不是只有 `updated_at`。

### 6.2 解析顺序

1. 优先使用句子中的显式日期，例如“9 月 24 日”“2026-09-24”。
2. 对“今天、明天、后天、昨天、上周、下周一”等确定性表达，使用消息 `occurred_at + timezone` 计算。
3. 对“过几天、最近、之前、以后”等范围模糊表达，保存区间或 `ambiguous`，不要擅自生成单日。
4. 对跨时区或用户明确指定的时区，使用指定时区，不使用服务器本地时区。
5. 解析结果经过范围校验：例如结束时间不得早于开始时间，日期不能因为处理延迟而漂移。
6. 解析成功后写入不可变的规范时间；后续馆长重复处理只读取该结果，不重新以当前时间计算。

### 6.3 何时需要 LLM 参与时间解析

LLM 只负责识别时间表达的语义角色和上下文，例如“后天”是在说考试、出差还是提醒。真正的日期换算交给确定性代码（Java `java.time` 或等价库）。

如果句子存在多种合理解释，LLM 返回候选和理由，系统将状态设为 `ambiguous`，在用户确认前不写入确定日期。例如“下周一”在跨时区对话中应结合用户时区；“周末”通常是一个区间而不是单个瞬间。

## 7. 数据模型调整

### 7.1 历史事实层

建议新增 `memory_fact`，或将等价字段扩展到 `long_term_memory`：

| 字段 | 说明 |
| --- | --- |
| `id` | 稳定事实 ID |
| `user_id` | 用户 ID |
| `predicate` | 规范谓词，如 `current_location`、`home_location` |
| `value_text` / `value_json` | 事实值 |
| `scope` | `current`、`stable`、`episodic`、`preference` |
| `assertion` | `observed`、`inferred`、`planned`、`corrected` |
| `confidence` | 0 到 1 |
| `valid_from` / `valid_to` | 事实有效区间 |
| `observed_at` | 被用户表达或系统观察到的时间 |
| `source_turn_id` | 原始回合 |
| `raw_text` | 证据原文或短摘录 |
| `status` | `active`、`superseded`、`expired`、`uncertain` |
| `supersedes_id` | 被当前事实替代的旧事实 |
| `created_at` / `updated_at` | 系统时间 |

历史事实永不因为画像更新而物理删除。比如“当前在南京”替代“当前在宜兴”时，宜兴事实标记 `superseded`，但“家在宜兴”这个不同谓词的事实保持 `active`。

### 7.2 当前画像投影

建议将 `user_profile` 明确作为当前状态投影，而不是事实总表：

```text
user_profile_current
  user_id
  slot_key                 -- current_location / home_location / ...
  value
  source_fact_id
  valid_from
  valid_to                 -- 当前值通常为空
  confidence
  updated_at
```

同一用户同一 `slot_key` 只允许一条当前记录。更新时在同一事务中：

1. 把旧画像值写入历史版本或由 `memory_fact` 保留；
2. 将旧事实标记为 `superseded`；
3. 写入新事实；
4. 更新 `user_profile_current` 指向新事实。

### 7.3 洞察和 MindPet 成长

用户画像、相处洞察和 MindPet 成长仍然分开：

- 画像：用户是谁、当前处于什么状态；
- 洞察：如何更好地和用户相处；
- 成长：MindPet 应长期保持的表达或行为；
- 事实/事件：可追溯的历史陈述和发生时间。

不要用 `llm_growth` 承担事实版本管理。`llm_growth` 可以引用 `source_fact_id`，但事实的有效期和替代关系应由事实层管理。

## 8. 冲突、覆盖和置信度策略

### 8.1 当前状态槽位

只有明确声明当前状态的事实才能更新 `*_current` 槽位。建议按以下优先级处理：

1. 用户明确纠正或明确当前陈述；
2. 多次独立回合重复确认；
3. 最近一次高置信度观察；
4. LLM 推断只能作为候选，不能直接覆盖明确事实。

“我去南京了”可以更新 `current_location`，但不能更新 `home_location`。如果用户说“以后我家搬到南京”，这是对 `home_location` 的明确变更，才允许替代宜兴。

### 8.2 歧义和低置信度

低置信度候选进入 `uncertain` 状态，不进入当前画像。工作记忆可以记录“待确认：用户可能近期在南京”，但不得把它当成确定事实注入系统提示词。

### 8.3 用户更正

更正不是覆盖原文，而是创建新的事实版本并关联 `supersedes_id`。这样可以审计“为什么画像从宜兴变为南京”，也可以在用户撤回更正时恢复上一版本。

## 9. 离线巩固流程

### 9.1 触发条件

- 每 15 个完整回合触发一次；
- 用户停止交互 5 分钟后触发一次小批次；
- 每日定时任务处理未完成或低置信度候选；
- 失败任务可通过指数退避重试；
- 保留 checkpoint，但 checkpoint 必须引用最后成功处理的真实回合 ID，不只依赖全局自增序号。

### 9.2 批处理步骤

1. 锁定用户的巩固任务，避免同一用户并发写入。
2. 读取未处理回合和前后文，附带每条消息的 `occurred_at` 与时区。
3. 加载当前画像、活动事实、已替代事实和已有洞察，供 LLM 去重和冲突判断。
4. 调用 LLM 生成结构化候选，不开放直接修改数据库的工具。
5. 对每个候选执行时间解析、谓词白名单、敏感信息过滤和置信度门槛检查。
6. 将候选与事实层做去重、补充、替代或待确认处理。
7. 在一个数据库事务中提交事实、画像投影、洞察和运行记录。
8. 更新工作记忆和 checkpoint；失败时保留原 checkpoint，记录失败原因。
9. 异步生成展示用的 MindPet 反思，不阻塞事实提交。

### 9.3 幂等键

建议使用以下组合避免重复：

```text
user_id + source_turn_id + predicate + normalized_value + normalized_start
```

同时为当前画像建立：

```text
UNIQUE(user_id, slot_key)
```

## 10. 对现有项目的改造拆分

### 阶段一：补齐时间和回合元数据

- 确认 `curator_turns.completed_at` 是消息发生时间，而不是后台处理时间；
- 增加用户时区来源和时间解析字段；
- 修复仅依赖自增 `sequence` 的 checkpoint，改为 `turn_id` 或用户维度序列；
- 为旧数据提供无法恢复时的 `unresolved` 标记，不强行回填日期。

### 阶段二：把馆长改为提案模式

- 保留现有每 15 轮、最近 20 轮的批处理节奏；
- 将 `saveUserProfile`、`saveUserInsight`、`saveLlmGrowth` 改为候选提案，或由馆长输出统一 JSON；
- 新增确定性 `TemporalNormalizer`、`FactMergeService` 和 `ProfileProjectionService`；
- 所有最终写入集中在一个事务中。

### 阶段三：拆分画像和事实

- 将 `user_profile` 定义为当前画像投影；
- 为事实增加谓词、有效期、来源、状态和替代关系；
- 迁移现有的通用 `location`、`profile` 等宽泛字段，无法判断的记录放入 `uncertain`，交给后续确认。

### 阶段四：时间关系和反思展示

- 在 `MemoryReflectionService` 的输入中同时提供规范时间和原始表达；
- 反思内容引用事实 ID，而不是复制一份没有版本关系的事实文本；
- 页面展示“发生于 2026-09-24”，需要时再展开“原文：后天”，避免用户看到未解析的模糊时间。

## 11. 验收用例

### 用例 A：地点状态分离

1. 9 月 20 日：“我家在宜兴。”
2. 9 月 22 日：“我去南京了。”

预期：

- `home_location = 宜兴`，状态 `active`；
- `current_location = 南京`，状态 `active`；
- 不产生“用户家在南京”；
- 历史事实仍可解释两次画像来源。

### 用例 B：相对日期固定锚定

1. 9 月 22 日：“后天考试。”
2. 9 月 25 日才执行馆长。

预期：

- `raw_time_expression = 后天`；
- `normalized_start = 9 月 24 日`；
- `time_anchor = message_occurred_at`；
- 9 月 25 日重试不会漂移到 9 月 27 日。

### 用例 C：模糊时间不伪造

用户说：“过几天可能去上海。”

预期：保存计划/可能性事实，时间状态为 `ambiguous` 或区间，不更新 `current_location`，不生成确定日期。

### 用例 D：明确搬家

用户说：“以后我家搬到南京了。”

预期：创建新的 `home_location = 南京`，将 `home_location = 宜兴` 标为 `superseded`，并保留替代链。

## 12. 风险与原则

- “不会出错”不能通过提示词承诺，只能通过时间锚定、结构化输出、规则校验、置信度和可追溯版本降低错误。
- 不要为了让画像看起来简洁而删除历史事实；当前视图简洁，事实层完整。
- 不要把 LLM 的推断直接写成用户明确说过的话。
- 不要在每次读取画像时重新调用 LLM 解析时间；解析应在事实写入时完成并持久化。
- 当系统无法确定时，保留不确定性比写入错误的精确日期更安全。

## 13. 逐文件改造规格

本节按照“低级模型可以直接执行”的粒度描述代码改造。每一步都应先完成编译和针对性测试，再进入下一步。

### 13.1 数据库迁移

新增文件：`MindPet-java/sql/migration_v8_memory_consolidation.sql`。

迁移内容：

1. 新增 `memory_fact` 表，字段至少包括：
   `id`、`user_id`、`predicate`、`value_text`、`value_json`、`scope`、`assertion`、`confidence`、`valid_from`、`valid_to`、`observed_at`、`event_timezone`、`raw_time_expression`、`normalized_start`、`normalized_end`、`time_precision`、`time_status`、`source_turn_id`、`raw_text`、`status`、`supersedes_id`、`created_at`、`updated_at`。
2. 新增 `user_profile_current` 表，唯一键为 `(user_id, slot_key)`，保存当前画像投影以及 `source_fact_id`、`confidence`、`valid_from`、`updated_at`。
3. 为 `curator_turns` 增加 `occurred_at`、`event_timezone`、`processed_at`、`consolidation_status` 字段。
4. 为 `curator_state` 增加 `last_turn_id`、`last_success_at`、`last_error`，将 checkpoint 从单纯的数字进度扩展为可追溯回合。
5. 增加索引：
   - `memory_fact(user_id, predicate, status, normalized_start)`；
   - `memory_fact(user_id, source_turn_id)`；
   - `user_profile_current(user_id, slot_key)`；
   - `curator_turns(user_id, consolidation_status, occurred_at)`。
6. 迁移旧 `user_profile` 时：
   - `category=state` 的记录按 `prop_key` 复制到 `user_profile_current`；
   - 不能可靠映射到槽位的记录保留在旧表，并标记为待迁移；
   - 不删除旧数据。

同时修改 `MindPet-java/src/main/resources/db/sqlite-schema.sql`，让全新数据库直接包含 v8 结构。迁移文件和 schema 必须保持等价。

### 13.2 回合时间和 checkpoint

修改 `MindPet-java/src/main/java/service/CuratorTurnStore.java`：

- 将 `append` 改为接收 `Instant occurredAt` 和 `String timezone`；
- 写入 `curator_turns.occurred_at`，不能用异步任务执行时间替代；
- 新增 `appendIfAbsent(userId, turnId, ...)`，让重试保持幂等；
- 新增 `pendingTurns(userId, lastTurnId, limit)`，按用户维度和时间排序取未处理回合；
- 新增 `markConsolidated(turnId, status)`；
- 新增 `saveCheckpoint(userId, lastTurnId, occurredAt)`；
- 保留旧 `checkpoint` 读取方法一段兼容期，但新逻辑不能只依赖全局自增 `sequence`；
- 当旧记录已被 500 条保留上限清理时，checkpoint 应跳到当前最早可用回合并记录 `gap_detected`，不能无限重试一个不存在的 target。

需要新增测试：

- 同一 `turnId` 重试不产生重复记录；
- 跨用户 sequence 不会影响单用户 pending 数量；
- 清理历史记录后馆长能够恢复处理，而不是永久空批次失败；
- `occurred_at` 与 `processed_at` 不同也不会改变时间锚点。

### 13.3 时间解析器

新增文件：`MindPet-java/src/main/java/service/TemporalNormalizer.java`。

职责：

- 输入：原始时间表达、回合 `occurredAt`、用户时区、LLM 返回的时间类型；
- 输出：`TemporalResolution`，包含 `normalizedStart`、`normalizedEnd`、`precision`、`status`、`anchor`、`timezone`；
- 使用 `java.time`，禁止使用系统默认时区作为隐式 fallback；
- 支持第一批确定性表达：今天、昨天、前天、明天、后天、上周、下周、周一至周日、X 月 X 日、YYYY-MM-DD；
- 对“过几天、最近、以后、下个月左右”等表达返回区间或 `ambiguous`；
- 对无关文本返回 `unresolved`，不能猜测；
- 结果必须可序列化并保存，后续读取不重新计算。

建议新增 `TemporalNormalizerTest.java`，至少覆盖：

```text
2026-09-22 + 后天 = 2026-09-24
2026-09-22 + 明天上午 = 2026-09-23，precision=hour 或 day
跨年：2026-12-31 + 明天 = 2027-01-01
Asia/Shanghai 与 UTC 输入不发生日期漂移
过几天 -> ambiguous，不生成固定日期
```

### 13.4 事实合并器

新增文件：`MindPet-java/src/main/java/service/FactMergeService.java`。

职责：

- 对谓词执行白名单校验；
- 按 `predicate + normalized value + time interval` 去重；
- 对 `current_*` 槽位执行替代关系；
- 对 `home_location`、`current_location` 等不同谓词禁止交叉覆盖；
- 低于置信度阈值的候选写入 `uncertain`，不投影到当前画像；
- 新事实和旧事实的状态变更必须在同一个事务中完成；
- 写入 `supersedes_id`，禁止物理删除历史事实。

推荐的默认策略：

| 条件 | 动作 |
| --- | --- |
| 明确当前陈述，置信度 >= 0.85 | 更新对应 `*_current` 槽位 |
| 明确纠正，置信度 >= 0.75 | 新事实替代旧事实 |
| 计划或推测 | 写入事实层，不更新当前画像 |
| 时间或语义存在歧义 | `uncertain`，等待确认 |
| 与稳定槽位语义不匹配 | 拒绝画像投影，保留审计记录 |

### 13.5 当前画像投影器

新增文件：`MindPet-java/src/main/java/service/ProfileProjectionService.java`。

修改 `MindPet-java/src/main/java/service/UserProfileService.java`：

- 保留 `save` 作为兼容入口，但新馆长不能直接调用它写入画像；
- 新增 `getCurrentProfile(userId)`，只从 `user_profile_current` 查询；
- 新增 `projectFact(factId)`，由事实合并器在事务内调用；
- `getProfileContext` 改为读取当前投影，并按 `slot_key` 输出；
- 输出中必须包含“当前”语义，不能把已替代值注入系统提示词；
- 对旧 `profile:summary` 记录保留兼容读取，但新数据不能继续写入一个无槽位的总摘要。

### 13.6 馆长服务改造

修改 `MindPet-java/src/main/java/service/MemoryCuratorService.java`：

1. 保留 `TRIGGER_INTERVAL=15` 和 `REVIEW_TURNS=20` 作为默认配置。
2. 将 `curate` 拆成以下方法：
   - `loadBatch`：读取未巩固回合和上下文；
   - `buildCuratorPrompt`：注入回合时间、时区、当前画像和活动事实；
   - `extractProposals`：调用 LLM，只接受结构化 JSON；
   - `validateProposals`：校验 schema、敏感信息、谓词和来源回合；
   - `normalizeTemporalExpressions`：调用 `TemporalNormalizer`；
   - `commitProposals`：调用 `FactMergeService`、`ProfileProjectionService` 和 `UserInsightService`；
   - `saveWorkingMemory`：只保存已提交事实衍生出的工作摘要。
3. 删除或停用 LLM 直接调用 `CuratorTools.saveUserProfile` 的路径；工具可以保留为兼容层，但默认不注册。
4. `parseSummary` 改为解析完整的 `CuratorProposal`，拒绝缺少 `source_turn_id` 的事实。
5. `recordRun` 增加 `proposals`、`accepted`、`rejected`、`uncertain`、`duration_ms`、`model` 和 `error_type`。
6. 失败时不推进 checkpoint；数据缺口时记录 `gap_detected` 并跳转到可用回合。

建议新增配置项：

```properties
memory.curator.trigger-interval=15
memory.curator.review-turns=20
memory.curator.idle-delay-seconds=300
memory.curator.min-profile-confidence=0.85
memory.curator.min-fact-confidence=0.60
memory.curator.max-retry=5
```

配置绑定可放在现有 Spring 配置模块；不要把阈值硬编码到 prompt 中。

### 13.7 Controller 和接口

修改 `MindPet-java/src/main/java/controller/DesktopMemoryController.java`：

- `/api/desktop/memory/portrait`：从 `ProfileProjectionService` 读取当前画像，从事实层读取带状态和时间的长期记忆；
- 新增 `GET /api/desktop/memory/curator/status` 返回 checkpoint、pending、最近运行、失败原因、数据缺口和耗时；
- 新增 `POST /api/desktop/memory/curator/retry`，只重试失败批次，不重复提交已经成功的事实；
- 新增 `GET /api/desktop/memory/facts?predicate=&status=`，用于实验和人工核对；
- 新增 `GET /api/desktop/memory/profile/history?slot=`，用于检查画像覆盖链；
- 对写入接口返回 `fact_id`、`resolution_status` 和 `projection_status`，便于端到端追踪。

修改 `MindPet/src/main/index.ts`：

- 增加上述 status、facts、profile history 的 IPC handler；
- 在 `MindPet/src/preload/index.ts` 和 `MindPet/src/preload/index.d.ts` 增加类型声明；
- 不让 renderer 自己推断时间或合并画像，所有规则走 Java 后端。

### 13.8 记忆展示层

修改 `MindPet-java/src/main/java/service/PortraitMemoryService.java`：

- `loadProfiles` 改为读取 `user_profile_current`；
- `loadDurableEpisodes` 增加 `status='active'` 或明确允许展示历史的筛选；
- 展示事实的 `normalized_start`，原文时间放入证据区；
- 对 `uncertain` 显示“待确认”，不能展示为确定事实；
- 保留 `source_turn_id` 和 `raw_text`，支持“查看原文”。

修改 `MindPet/src/renderer/src/pages/MemoryGalleryPage.tsx`：

- 当前画像区域只展示 `user_profile_current`；
- 事实详情同时显示“规范时间”和“原始表达”；
- 对 `superseded` 默认折叠，但允许查看历史版本；
- 对 `uncertain` 增加待确认状态，不提供确定事实样式；
- 增加馆长状态入口：待处理数量、最近运行、失败重试。

### 13.9 测试文件

新增或修改以下测试：

- `MindPet-java/src/test/java/service/TemporalNormalizerTest.java`
- `MindPet-java/src/test/java/service/FactMergeServiceTest.java`
- `MindPet-java/src/test/java/service/ProfileProjectionServiceTest.java`
- `MindPet-java/src/test/java/service/MemoryCuratorServiceTest.java`
- `MindPet-java/src/test/java/service/CuratorTurnStoreTest.java`
- 修改 `MindPet-java/src/test/java/service/StorageRegressionTest.java`，覆盖 v8 schema、索引和迁移。

集成测试必须使用临时 SQLite 数据库，不连接真实模型。LLM 输出通过固定 JSON fixture 注入，另加少量真实模型回放测试验证 prompt 兼容性。

## 14. 低级模型执行顺序

严格按以下顺序执行，避免前后端同时改动导致无法定位问题：

1. 写 v8 schema 和迁移，运行数据库回归测试。
2. 修改 `CuratorTurnStore` 的发生时间、幂等和 checkpoint，运行存储测试。
3. 新增 `TemporalNormalizer`，先只写单元测试，达到 100% 规则分支覆盖后再接入馆长。
4. 新增 `memory_fact` DAO 或 service，完成事实 CRUD、状态替代和唯一约束测试。
5. 新增 `FactMergeService` 和 `ProfileProjectionService`，完成宜兴/南京用例。
6. 将 `MemoryCuratorService.curate` 拆成提案、归一化、提交三个阶段，先使用 fixture，不接真实 LLM。
7. 接入真实 LLM，但仍禁止直接写库；记录提案通过率和拒绝原因。
8. 修改 Controller、IPC 和 preload 类型，暴露 status、facts、profile history。
9. 修改 `PortraitMemoryService` 和 `MemoryGalleryPage` 展示规范时间、状态和历史链。
10. 运行实验方案中的离线回放、并发、延迟和资源测试，达到门槛后再打开默认自动巩固。

每一步提交都应能独立编译。任何一步失败，不要跳过测试直接继续下一步。

## 15. 回滚策略

- 通过配置开关关闭新馆长：`memory.curator.mode=legacy|proposal|commit`；
- `legacy` 保留当前画像写入行为，但不启用新事实投影；
- `proposal` 只生成候选并记录，不修改画像；
- `commit` 才允许事务提交事实和当前画像；
- v8 新表只追加数据，不修改或删除旧表内容；
- 出现异常时可停止 `commit`，保留 proposal 和运行记录，待修复后重放。
