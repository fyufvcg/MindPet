# MindPet Retrieval Ablation — RRF Normalization V2

## 1. 环境与 Benchmark

- Git commit：`c39c472ef63bde8fe323da423f0420b88398cdc9`
- 运行时间：`2026-09-21T14:34:37.543834+00:00` 至 `2026-09-21T14:34:48.532907+00:00`
- 数据库：`mindpet_eval`；用户：`eval_test_user`
- Benchmark：120 memories / 40 queries
- Embedding：`bge-m3`，1024 维
- API：`http://127.0.0.1:8081/api/eval/memory/search`

## 2. 模式与指标定义

比较 keyword_only、vector_only、rrf、mindpet_full、mindpet_full_rrf_norm。每个 query/mode 只请求一次 Top10，K=1/3/5/10 均由同一排名切片得到。主排名指标仅统计 36 条有答案查询。Precision@K 的分母固定为 K；Recall@K 以 ground truth 数量为分母；MRR@10 取前十首个相关结果的倒数排名；nDCG 使用二元相关性。

## 3. 总体结果

| mode | Precision@1 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|---|---|---|
| keyword_only | 0.75 | 0.722222222222 | 0.930555555556 | 0.944444444444 | 0.972222222222 | 0.851851851852 | 0.874425671219 |
| vector_only | 0.861111111111 | 0.819444444444 | 0.944444444444 | 0.986111111111 | 1 | 0.915277777778 | 0.937836600759 |
| rrf | 0.861111111111 | 0.805555555556 | 0.958333333333 | 0.972222222222 | 1 | 0.921296296296 | 0.937698375465 |
| mindpet_full | 0.222222222222 | 0.222222222222 | 0.486111111111 | 0.638888888889 | 0.916666666667 | 0.423875661376 | 0.540367915782 |
| mindpet_full_rrf_norm | 0.833333333333 | 0.791666666667 | 0.944444444444 | 0.958333333333 | 1 | 0.896296296296 | 0.91454144483 |

## 4. Query Type 切片

| query_type | mode | query_count | Precision@1 | Recall@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|---|---|
| exact_keyword | keyword_only | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | vector_only | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | rrf | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | mindpet_full | 8 | 0.25 | 1 | 0.427579365079 | 0.561964555643 |
| exact_keyword | mindpet_full_rrf_norm | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| semantic_paraphrase | keyword_only | 10 | 0.5 | 0.9 | 0.7 | 0.752371901429 |
| semantic_paraphrase | vector_only | 10 | 0.7 | 1 | 0.82 | 0.864871231438 |
| semantic_paraphrase | rrf | 10 | 0.7 | 1 | 0.816666666667 | 0.861806669425 |
| semantic_paraphrase | mindpet_full | 10 | 0.3 | 0.9 | 0.469444444444 | 0.57059804351 |
| semantic_paraphrase | mindpet_full_rrf_norm | 10 | 0.7 | 1 | 0.81 | 0.855092433346 |
| hybrid | keyword_only | 8 | 0.75 | 1 | 0.833333333333 | 0.875 |
| hybrid | vector_only | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| hybrid | rrf | 8 | 0.75 | 1 | 0.875 | 0.907732438393 |
| hybrid | mindpet_full | 8 | 0.125 | 1 | 0.316666666667 | 0.476460194458 |
| hybrid | mindpet_full_rrf_norm | 8 | 0.875 | 1 | 0.916666666667 | 0.9375 |
| multi_candidate | keyword_only | 6 | 0.666666666667 | 1 | 0.833333333333 | 0.825934191598 |
| multi_candidate | vector_only | 6 | 0.833333333333 | 1 | 0.875 | 0.913745926565 |
| multi_candidate | rrf | 6 | 1 | 1 | 1 | 0.97953588589 |
| multi_candidate | mindpet_full | 6 | 0 | 0.666666666667 | 0.310185185185 | 0.396376703432 |
| multi_candidate | mindpet_full_rrf_norm | 6 | 0.833333333333 | 1 | 0.888888888889 | 0.873606321139 |
| temporal_importance | keyword_only | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | vector_only | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | rrf | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | mindpet_full | 4 | 0.5 | 1 | 0.6875 | 0.765401577911 |
| temporal_importance | mindpet_full_rrf_norm | 4 | 1 | 1 | 1 | 1 |

## 5. No-answer 诊断

当前系统没有正式拒答阈值；empty result 仅作诊断，不能解释为 no-answer accuracy。

| mode | no_answer_query_count | empty_result_count@1 | empty_result_rate@1 | avg_returned_count@10 |
|---|---|---|---|---|
| keyword_only | 4 | 1 | 0.25 | 7.25 |
| vector_only | 4 | 0 | 0 | 10 |
| rrf | 4 | 0 | 0 | 10 |
| mindpet_full | 4 | 0 | 0 | 10 |
| mindpet_full_rrf_norm | 4 | 0 | 0 | 10 |

## 6. 只读与一致性验证

- 正式运行窗口开始前的最小健康检查：HTTP 200，响应 `status=OK`。
- 正式请求严格为 40 queries × 5 modes = 200 次；成功 200，失败 0。
- Raw：200 行；每种模式 40 条；有答案查询 36 条；no-answer 4 条。
- 所有 HTTP 状态为 200、响应 status 为 OK、DB ID 均可映射到 Benchmark ID。
- access_count 变化 0 条；last_accessed 变化 0 条。

## 7. 异常与未命中查询

HTTP/JSON/映射/一致性失败：无。以下仅列出有答案查询的 Top10 未命中，不等同于运行错误：
- `keyword_only`：q010
- `vector_only`：无 Top10 miss
- `rrf`：无 Top10 miss
- `mindpet_full`：q016, q028
- `mindpet_full_rrf_norm`：无 Top10 miss

## 8. Query-level Rank Comparison

- Full V1 → Full V2：improved=25，unchanged=10，degraded=1，avg rank delta=-2.694444。
- RRF → Full V2：improved=1，unchanged=31，degraded=4，avg rank delta=0.194444。

Full V1 → Full V2 唯一退化的查询是 `q010`（semantic_paraphrase）：首个相关结果由 rank 9 下降到 rank 10。RRF → Full V2 的非持平查询如下：

| query_id | query_type | RRF rank | Full V2 rank | result |
|---|---|---:|---:|---|
| q005 | exact_keyword | 1 | 2 | degraded |
| q010 | semantic_paraphrase | 6 | 10 | degraded |
| q020 | hybrid | 2 | 1 | improved |
| q025 | hybrid | 2 | 3 | degraded |
| q030 | multi_candidate | 1 | 3 | degraded |

## 9. H1 结论边界

### 9.1 Full V1 与 Full V2

| metric | Full V1 | Full V2 | absolute change |
|---|---:|---:|---:|
| Precision@1 | 0.222222 | 0.833333 | +0.611111 |
| MRR@10 | 0.423876 | 0.896296 | +0.472421 |
| nDCG@10 | 0.540368 | 0.914541 | +0.374174 |
| Recall@10 | 0.916667 | 1.000000 | +0.083333 |

Full V2 在四个核心指标上均高于 Full V1；36 条有答案查询中有 25 条首个相关结果排名改善、10 条不变、1 条退化。平均 rank delta 为 -2.694444，说明改进不仅来自少数查询。

### 9.2 RRF 与 Full V2

| metric | RRF | Full V2 | Full V2 - RRF |
|---|---:|---:|---:|
| Precision@1 | 0.861111 | 0.833333 | -0.027778 |
| MRR@10 | 0.921296 | 0.896296 | -0.025000 |
| nDCG@10 | 0.937698 | 0.914541 | -0.023157 |
| Recall@10 | 1.000000 | 1.000000 | 0.000000 |

Full V2 已接近但仍略低于 RRF：31 条查询排名不变，1 条改善，4 条退化。因此归一化消除了 Full V1 的大部分排序退化，但没有证明 metadata rerank 对所有查询都有益，也没有完全消除残余退化。

### 9.3 Query Type 变化

| query_type | RRF MRR@10 | Full V1 MRR@10 | Full V2 MRR@10 | V2 - V1 |
|---|---:|---:|---:|---:|
| exact_keyword | 1.000000 | 0.427579 | 0.937500 | +0.509921 |
| semantic_paraphrase | 0.816667 | 0.469444 | 0.810000 | +0.340556 |
| hybrid | 0.875000 | 0.316667 | 0.916667 | +0.600000 |
| multi_candidate | 1.000000 | 0.310185 | 0.888889 | +0.578704 |
| temporal_importance | 1.000000 | 0.687500 | 1.000000 | +0.312500 |

`temporal_importance` 从 0.687500 恢复到 1.000000，与 RRF 持平；`multi_candidate` 从 0.310185 提升到 0.888889，但仍低于 RRF 的 1.000000。

### 9.4 对 H1 的判断

本轮数据支持 H1：在这个固定 Benchmark v1 和本次运行中，原始 RRF 分数未归一化造成的量级失衡，是 Full V1 排序退化的主要原因。依据是仅改变 evaluation-only Full 模式中的 RRF normalization 后，P@1、MRR@10、nDCG@10 和 R@10 均显著恢复，25/36 条查询排名改善，且五类 query type 的 MRR 全部提升。

这一结论不能外推为“当前权重已经最优”或“metadata rerank 一定有效”。Full V2 仍在 4 条查询上弱于 RRF，整体 P@1、MRR@10、nDCG@10 也仍略低于 RRF；同一已观察过的 Benchmark v1 不能作为独立泛化证明。

## 10. 已知限制

- 单用户、中文、120 条人工 Benchmark，不能代表真实用户总体分布。
- 相关性为二元标注，尚无多标注者一致性或分级 gain。
- No-answer 没有拒答阈值，因此只能报告返回数量诊断。
- 本轮仍使用开发阶段已经观察过的 Benchmark v1，因此不能作为最终独立泛化证明。
- 本报告不包含调参、删样本、grid search 或重复请求择优。
- 正式实验期间未修改 Java 检索算法、Benchmark、ground truth、embedding、importance、时间字段或评分公式。
