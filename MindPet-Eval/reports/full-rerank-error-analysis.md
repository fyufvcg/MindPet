# MindPet Full Rerank Query-level Error Analysis

本报告只分析既有 `retrieval_ablation_raw.jsonl` 和 `queries.jsonl`，没有重新请求 API、运行实验或修改算法。Top10 未出现 relevant 时，error analysis rank 记为 11；正式 MRR 定义不变。

## 1. 第一轮结果摘要

| mode | P@1 | R@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|
| keyword_only | 0.750000 | 0.972222 | 0.851852 | 0.874426 |
| vector_only | 0.861111 | 1.000000 | 0.915278 | 0.937837 |
| rrf | 0.861111 | 1.000000 | 0.921296 | 0.937698 |
| mindpet_full | 0.222222 | 0.916667 | 0.423876 | 0.540368 |

## 2. RRF vs Full Rank Delta

- improved：0 / 36 (0.00%)
- unchanged：9 / 36 (25.00%)
- degraded：27 / 36 (75.00%)
- 平均 rank delta（Full - RRF）：2.888889

## 3. 各 Query Type Degradation

| query_type | query_count | improved | unchanged | degraded | avg_rank_delta |
|---|---|---|---|---|---|
| exact_keyword | 8 | 0 | 2 | 6 | 3.000000 |
| semantic_paraphrase | 10 | 0 | 4 | 6 | 2.600000 |
| hybrid | 8 | 0 | 1 | 7 | 3.250000 |
| multi_candidate | 6 | 0 | 0 | 6 | 4.000000 |
| temporal_importance | 4 | 0 | 2 | 2 | 1.000000 |

## 4. Retrieval 与 Metadata 贡献

Full 分解：`retrievalContribution = 0.5 × rrfScore`；`metadataContribution = 0.2 × timeScore + importanceContribution + confidenceContribution + highImportanceBonus`。importanceContribution 已是 `importance × 0.2`，未重复相乘。

| 候选组 | 数量 | 平均 retrievalContribution | 平均 metadataContribution | 平均有限 ratio | INF 数量 |
|---|---:|---:|---:|---:|---:|
| degraded 中错误上位 memory | 112 | 0.0090231218 | 0.4360114746 | 53.5928703737 | 0 |
| degraded 中 Top10 relevant memory | 27 | 0.0159488441 | 0.4192451450 | 26.7685764690 | 0 |

在可直接成对比较的 92 个“错误上位 memory / 首个 Full relevant memory”组合中，有 89 个组合同时满足：错误 memory 的 retrievalContribution 更低、metadataContribution 更高，但 finalScore 更高。

## 5. 典型失败案例

| query | type | relevant | RRF first rank | Full first rank | delta | Full rank 1 error |
|---|---|---|---:|---:|---:|---|
| q028 要探索数据并快速画图，我会打开哪个 Python 工具？ | multi_candidate | m044 | 1 | 11 | 10 | m092 MindPet 的长期记忆存储使用 PostgreSQL 和 pgvector。 |
| q016 想找家安静的小店聊天，我最可能选哪里？ | semantic_paraphrase | m071 | 2 | 11 | 9 | m109 我的紧急联系人是姐姐小雨。 |
| q008 杭州 | exact_keyword | m075 | 1 | 9 | 8 | m101 我现在常住南京。 |
| q030 桌面上常用的键盘和鼠标分别是什么？ | multi_candidate | m082, m083 | 1 | 9 | 8 | m092 MindPet 的长期记忆存储使用 PostgreSQL 和 pgvector。 |
| q005 DBeaver | exact_keyword | m049 | 1 | 7 | 6 | m092 MindPet 的长期记忆存储使用 PostgreSQL 和 pgvector。 |
| q014 休息日没有安排时，我一般几点才起床？ | semantic_paraphrase | m052 | 1 | 6 | 5 | m035 遇到紧急需求时，我要求先用文字确认范围和验收标准。 |
| q015 大学里认识的哪位朋友常约我一起打球？ | semantic_paraphrase | m062 | 1 | 6 | 5 | m109 我的紧急联系人是姐姐小雨。 |
| q019 周末复习数据库时我会看什么？ | hybrid | m023 | 1 | 6 | 5 | m042 开发 Java 项目时我习惯使用 IntelliJ IDEA。 |

Top10 外 relevant 的 Full 分数不在 raw 中，因此 q016、q028 的正确 memory Full 贡献字段保留为空，不做推断。完整逐候选字段见 degraded cases CSV。

## 6. RRF / Full Score Scale

- `RRF_max = 2/61 = 0.0327868852`
- `0.5 × RRF_max = 0.0163934426`
- `0.2 × timeScore ≤ 0.2`
- `importanceContribution ≤ 0.2`
- `confidenceContribution ≤ 0.05`
- `highImportanceBonus ≤ 0.05`
- metadata theoretical max = 0.50
- metadata_max / retrieval_max = 30.5

该尺度比较只描述当前公式的数值范围，不提出或实施权重修改。

## 7. Top10 Recall 与 MRR 差异

- 至少一个 relevant 位于 Full Top10：34 / 36
- Full Top10 内没有 relevant：2 / 36
- 首个 relevant rank > 1：28 / 36
- 首个 relevant rank > 3：18 / 36
- 首个 relevant rank > 5：12 / 36
- Full R@10 为 0.916667，而 MRR@10 为 0.423876。多数查询仍在 Top10 找到至少一个相关项，但正确项经常被移动到较后位置；多 relevant query 的 R@10 还会受是否召回全部 relevant 影响。

## 8. 当前数据可以支持的结论

- RRF 的候选相关性信号总体正常：36 条有答案查询中，RRF R@10 为 1.0、MRR@10 为 0.921296。
- Full 相对 RRF 有 27 条退化、0 条改善，主要差异发生在 rerank 顺序而非候选完全缺失。
- 在本轮 raw 的 degraded cases 中，错误上位候选平均 retrievalContribution 低于 relevant 候选，但平均 metadataContribution 更高；finalScore 的确定性分解显示 metadata 项足以覆盖 retrieval 差距。
- 因此，本轮数据支持“当前 Full 公式中的相关性贡献与 metadata 贡献存在显著量级失衡，并导致排序退化”这一针对本 Benchmark 和本次运行的结论。

## 9. 当前数据不能支持的结论

- 不能据此确定新的最优权重、归一化方法或通用阈值；本阶段没有做任何反事实调参实验。
- 不能外推到其他用户、语言、数据规模或真实生产分布。
- 不能把 Top10 外 relevant 的 Full 分数补算或猜测；API raw 没有提供这些候选的 rerank 字段。
- 不能断言 time、importance、confidence 或 bonus 中某一个单项独立造成全部退化；当前只验证了它们合计后的尺度和排序效应。
