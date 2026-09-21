# MindPet Retrieval Ablation Run

## 1. 环境与 Benchmark

- Git commit：`00a0a8b4a17af885d69dc7b93ca0f8e7c7536ac9`
- 运行时间：`2026-09-21T11:47:22.098772+00:00` 至 `2026-09-21T11:47:34.412840+00:00`
- 数据库：`mindpet_eval`；用户：`eval_test_user`
- Benchmark：120 memories / 40 queries
- Embedding：`bge-m3`，1024 维
- API：`http://127.0.0.1:8081/api/eval/memory/search`

## 2. 模式与指标定义

比较 keyword_only、vector_only、rrf、mindpet_full。每个 query/mode 只请求一次 Top10，K=1/3/5/10 均由同一排名切片得到。主排名指标仅统计 36 条有答案查询。Precision@K 的分母固定为 K；Recall@K 以 ground truth 数量为分母；MRR@10 取前十首个相关结果的倒数排名；nDCG 使用二元相关性。

## 3. 总体结果

| mode | Precision@1 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|---|---|---|
| keyword_only | 0.75 | 0.722222222222 | 0.930555555556 | 0.944444444444 | 0.972222222222 | 0.851851851852 | 0.874425671219 |
| vector_only | 0.861111111111 | 0.819444444444 | 0.944444444444 | 0.986111111111 | 1 | 0.915277777778 | 0.937836600759 |
| rrf | 0.861111111111 | 0.805555555556 | 0.958333333333 | 0.972222222222 | 1 | 0.921296296296 | 0.937698375465 |
| mindpet_full | 0.222222222222 | 0.222222222222 | 0.486111111111 | 0.638888888889 | 0.916666666667 | 0.423875661376 | 0.540367915782 |

## 4. Query Type 切片

| query_type | mode | query_count | Precision@1 | Recall@10 | MRR@10 | nDCG@10 |
|---|---|---|---|---|---|---|
| exact_keyword | keyword_only | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | vector_only | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | rrf | 8 | 1 | 1 | 1 | 1 |
| exact_keyword | mindpet_full | 8 | 0.25 | 1 | 0.427579365079 | 0.561964555643 |
| semantic_paraphrase | keyword_only | 10 | 0.5 | 0.9 | 0.7 | 0.752371901429 |
| semantic_paraphrase | vector_only | 10 | 0.7 | 1 | 0.82 | 0.864871231438 |
| semantic_paraphrase | rrf | 10 | 0.7 | 1 | 0.816666666667 | 0.861806669425 |
| semantic_paraphrase | mindpet_full | 10 | 0.3 | 0.9 | 0.469444444444 | 0.57059804351 |
| hybrid | keyword_only | 8 | 0.75 | 1 | 0.833333333333 | 0.875 |
| hybrid | vector_only | 8 | 0.875 | 1 | 0.9375 | 0.953866219196 |
| hybrid | rrf | 8 | 0.75 | 1 | 0.875 | 0.907732438393 |
| hybrid | mindpet_full | 8 | 0.125 | 1 | 0.316666666667 | 0.476460194458 |
| multi_candidate | keyword_only | 6 | 0.666666666667 | 1 | 0.833333333333 | 0.825934191598 |
| multi_candidate | vector_only | 6 | 0.833333333333 | 1 | 0.875 | 0.913745926565 |
| multi_candidate | rrf | 6 | 1 | 1 | 1 | 0.97953588589 |
| multi_candidate | mindpet_full | 6 | 0 | 0.666666666667 | 0.310185185185 | 0.396376703432 |
| temporal_importance | keyword_only | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | vector_only | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | rrf | 4 | 1 | 1 | 1 | 1 |
| temporal_importance | mindpet_full | 4 | 0.5 | 1 | 0.6875 | 0.765401577911 |

## 5. No-answer 诊断

当前系统没有正式拒答阈值；empty result 仅作诊断，不能解释为 no-answer accuracy。

| mode | no_answer_query_count | empty_result_count@1 | empty_result_rate@1 | avg_returned_count@10 |
|---|---|---|---|---|
| keyword_only | 4 | 1 | 0.25 | 7.25 |
| vector_only | 4 | 0 | 0 | 10 |
| rrf | 4 | 0 | 0 | 10 |
| mindpet_full | 4 | 0 | 0 | 10 |

## 6. 只读与一致性验证

- Raw：160 行；每种模式 40 条；有答案查询 36 条；no-answer 4 条。
- 所有 HTTP 状态为 200、响应 status 为 OK、DB ID 均可映射到 Benchmark ID。
- access_count 变化 0 条；last_accessed 变化 0 条。

## 7. 异常与未命中查询

HTTP/JSON/映射/一致性失败：无。以下仅列出有答案查询的 Top10 未命中，不等同于运行错误：
- `keyword_only`：q010
- `vector_only`：无 Top10 miss
- `rrf`：无 Top10 miss
- `mindpet_full`：q016, q028

## 8. 已知限制

- 单用户、中文、120 条人工 Benchmark，不能代表真实用户总体分布。
- 相关性为二元标注，尚无多标注者一致性或分级 gain。
- No-answer 没有拒答阈值，因此只能报告返回数量诊断。
- 本报告如实记录第一轮固定算法结果，不包含调参、删样本或图表。
