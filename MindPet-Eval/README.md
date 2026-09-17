# MindPet-Eval

独立于 React/Electron 的长期记忆检索消融实验框架。

## 当前状态：设计/框架待审核

本轮只建立目录、配置契约与 Python 入口骨架，未修改 Java，未接通实验 API，未生成/导入数据，未运行检索或指标计算。**没有任何实验结果。**

- 算法说明：`../docs/memory-retrieval-algorithm.md`。
- 接入方案：`reports/retrieval-mode-design.md`，请先审核。
- 本轮实际框架验证记录：`reports/scaffold-check.md`，不属于实验结果。
- `scripts/*.py` 的 `--help` 可运行；真正执行明确失败，不发送 HTTP、不写结果。
- `metrics/retrieval_metrics.py` 目前只有接口/公式契约，调用会抛出 `NotImplementedError`。
- 两个 dataset JSONL 为有意保留的空文件，不是已经完成的小数据集。

## 目录

```text
MindPet-Eval/
├── README.md
├── requirements.txt
├── configs/retrieval.yaml
├── datasets/retrieval/{memories,queries}.jsonl
├── scripts/
│   ├── generate_small_dataset.py
│   ├── run_retrieval_ablation.py
│   └── evaluate_retrieval.py
├── metrics/retrieval_metrics.py
├── results/{raw,tables,figures}/
└── reports/retrieval-mode-design.md
```

## 环境与当前可运行检查

Python 3.11+。本轮只用标准库验证入口；尚未安装 requirements。未来真实实验前需记录 Python/JDK/依赖版本及源码哈希。

```powershell
Set-Location D:\MindPet-master\MindPet-Eval
python -B scripts/generate_small_dataset.py --help
python -B scripts/run_retrieval_ablation.py --help
python -B scripts/evaluate_retrieval.py --help
```

依赖安装方式（以后实施时执行，本轮未执行）：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

requirements 目前是允许范围，**不是已验证的锁定环境**。真实实验前使用成功安装的环境生成独立 lock 文件并保留版本记录。

## 数据格式与后续小数据集计划

后续生成 120 条 memory、40 条 query，seed=42，全部隔离为 `user_id=eval_test_user`。此 user_id 沿用项目实验隔离约定；需求中的 `eval-user` 是示例，不能混用生产用户。

memories.jsonl 每行 JSON（下例只是格式示例，不是实验数据或结果）：

```json
{"memory_id":"m001","user_id":"eval_test_user","content":"我最喜欢打羽毛球","importance":0.8,"confidence":1.0,"created_at":"2026-09-17T10:00:00+08:00","emotion":"neutral","layer":2,"last_accessed":"2026-09-17T10:00:00+08:00","access_count":0}
```

queries.jsonl：

```json
{"query_id":"q001","query":"我平时喜欢什么运动？","relevant_memory_ids":["m001"]}
```

最后访问时间/访问次数是实验控制字段，不能在导入时让默认 NOW() 悄悄覆盖数据集意图。数据库主键是实际 id；数据集 memory_id 必须通过真实导入映射到数据库 id。该映射后续保存到 `results/raw/memory_id_mapping.json`，现在没有创建。

规划覆盖人物关系、偏好、目标、时间、工作项目、语义改写、关键词明显、语义明显、相似干扰、旧/新信息。初步按 10 类各 12 条 memory、4 条 query；最终标签人工核对。先建立链路，不为了证明 Full 优越而挑选结果。120 条会触发关键词前 100 候选池限制，要单独报告截断影响。

生成时记录基准时间与相对年龄，合理控制 G>0.1 的过滤边界；可包含明确标注的过滤测试项。固定种子之外还需保存数据文件和时间元信息，不能声称仅靠 seed 即可抵消时间衰减。

## 后续执行流程（当前不可执行）

1. 审核并实现 Java 默认关闭的实验接口，完成回归测试。
2. 在独立测试库生成/导入数据，仅使用 eval_test_user，保存真实 id 映射。
3. 实现并单测指标，再运行四种模式。
4. 每个 query 取 topK=10，以返回前缀计算 K=1/3/5/10；各路 Java 候选上限仍是 20。
5. 保存逐 query 原始结果/失败状态，再独立汇总指标和绘图。

未来计划命令形式（脚本现在会显式报 NOT_IMPLEMENTED）：

```powershell
python scripts/generate_small_dataset.py --config configs/retrieval.yaml
python scripts/run_retrieval_ablation.py --config configs/retrieval.yaml
python scripts/evaluate_retrieval.py --config configs/retrieval.yaml
```

不能用 `DesktopMemoryController` 的固定 desktop-user 接口导入或搜索实验数据。本轮不存在导入脚本；导入需要后续单独审核，不默认授权生产数据库写入。

## 指标约定（待实现并单测）

- Precision@K：前 K 个结果里相关项数量 / K；返回少于 K 时仍除以 K。
- Recall@K：前 K 个结果覆盖的相关项数量 / 全部标注相关项数量。
- MRR：每个 query 第一条相关结果的倒数名次，未命中为 0，再对 query 求平均；本轮截断列表上限为 10，应标明这是检索深度 10 下的 MRR。
- nDCG@K：二值标签，DCG=sum(rel_i/log2(i+1))，IDCG 按 min(K,相关项数量) 个理想命中计算。
- query 相关标签不能为空；返回重复 id 不得重复算命中，应验证原始列表并报告异常。
- 不允许把 SQL/Embedding 失败当成正常“没有相关项”。

## 输出契约（未生成）

- `results/raw/retrieval_results.jsonl`：query_id、mode、status、数据库 id 与 dataset id、真实 latency_ms、候选 debug 分量、异常。
- `results/tables/retrieval_metrics.csv`：每种模式的 precision@1/3/5/10、recall@1/3/5/10、mrr、ndcg@1/3/5/10、平均/P95 延迟及状态/成功失败计数。
- `results/figures/`：后续真实 CSV 的对比图。

若某模式存在失败，正式全量指标留空并标 FAILED/PARTIAL，不能偷偷从分母剔除失败 query；可另外生成明确标记的“成功子集”诊断统计，但不充当完整实验结论。

results 目录只有 `.gitkeep`，**没有占位分数或虚假的 CSV**。重跑时后续 runner 必须拒绝无意覆盖已有 run，记录 run_id、数据哈希、源码哈希、开始/结束时间、环境和 embedding 模型；四组请求不更新访问状态。

## 业务安全边界

Java `search()` 有 touchAccessed 副作用，不能直接用于四组顺序对比。方案中的实验查询不触发 touchAccessed、append、prune、馆长或图谱写入。实验 API 默认不存在，需显式启用、令牌和固定测试用户；优先连接独立测试库。

这只能在后续实现和测试通过后成立。本轮业务代码没有改动，框架也没有访问数据库。
