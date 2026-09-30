# Java 记忆馆长基线与系统评测

最后更新：2026-09-30。当前正式结果和口径以 [G1 / G2A / G2C 中文对照报告](results/java-ablation-v4-formal-comparison-20260930.md) 为准。本文件此前描述的单组检索、fixture 和未运行状态已经过时。

## 当前正式评测

两组评测均使用真实 LLM 调用执行生产 Java 服务，没有使用 fixture 输出作为实验数据。运行配置记录的模型为 gpt-6-luna，数据 split 为 locked_test：

- 普通样本：[报告](results/java-ablation-v4-formal-normal-20260930/report.md)、[指标](results/java-ablation-v4-formal-normal-20260930/metrics.json)、[配置](results/java-ablation-v4-formal-normal-20260930/run-config.json)。输入文件 SHA-256 为 a87ef16b7fdace3fbac85ca2b3c10969c1941c9de7f709309b2a80fc027b202b。
- 高重复样本：[报告](results/java-ablation-v4-formal-redundant-20260930/report.md)、[指标](results/java-ablation-v4-formal-redundant-20260930/metrics.json)、[配置](results/java-ablation-v4-formal-redundant-20260930/run-config.json)。输入文件 SHA-256 为 8960fa8483de3a6f9f94fe2e3850ebf9882214363ff5dbe83cc2b2568f3ad5ba。

每份测试集包含 16 条正式时间线、640 条输入和 192 道问题。每道问题由三个组分别回答，因此每个样本有 576 个真实 LLM 回答。所有答案都使用本组生产检索 Top-10 上下文；对 5,760 个答案来源 ID 的独立复核全部通过。问答和匿名裁判完整，模型调用失败为 0。

## 对照组定义

- **G1 基线：**逐条输入生产 KnowledgeGraphService，再写入普通长期记忆。640 次 baseline_memory 模型调用用于每轮的知识图谱事实/关系抽取；G1 没有馆长整理和压缩步骤，因此不能把它描述为“完全不调用 LLM”。
- **G2A 追加消融：**复用 G1 的原始输入和 KG 侧车，将同一批真实 MemoryCuratorService 接受结果追加为可检索内容，不移除重复或被覆盖内容。它测量“加入馆长抽取但不压缩”的效果。
- **G2C 压缩系统：**复用同一批馆长输出，执行生产 MemoryCuratorCommitService 和 MemoryCorpusCompactionService；压缩后仍保留来源证据、原文和历史/回滚信息。原始单位只有在来源内容被可检索 canonical facts 覆盖后才会退出当前检索集合，未覆盖细节保留为 residual。

G1、G2A、G2C 保留相同 KG 内容作为检索侧车并将其计入语料 token。G1/G2A 使用 SqliteMemoryService 混合检索；G2C 执行其生产候选合并、过滤和语义去重流程。共享字符 n-gram 排序器只提供诊断数据，报告的主 Recall@5/Recall@10 和回答上下文来自各组生产检索结果。

每道问题触发一份匿名裁判请求，裁判看到打乱组别顺序的三份回答并给出 0、0.5 或 1 分。规则正确率和裁判正确率分开报告；裁判正确率只把 1 分算作正确。

## 样本与设计边界

输入来自 ablation-v4 的普通与高重复合成语料，正式数据各有 16 条 locked-test 时间线、40 轮/时间线和 12 道/时间线问题。生成器测得语义重复轮次分别占 22.5% 与 60.0%。两组共享核心事实、状态变化和问题，但普通组包含额外独立经历，因此样本之间并非仅重复率单变量变化；比较用于观察两种压缩难度，不作为真实用户总体的因果估计。

生成数据位于 data/ablation-v4/normal.jsonl 与 data/ablation-v4/redundant.jsonl。每份 JSONL 文件包含 20 条时间线，其中正式运行选取 locked_test 的 16 条。后续若基于本次结果继续调参，应新建数据版本和未查看的留出集；当前 locked_test 已用于正式结果，不应再作为独立验证集。

## 指标口径

- 检索主指标：Recall@5 和 Recall@10。Recall@1 不属于本次报告指标。另记录 Precision@5/10、MRR、NDCG@10、256/512/1024-token 预算召回、上下文长度和 Top-10 重复占位。
- 系统压缩：G2C 相对 G1 的可检索语料 token 减少比例；G2C 相对 G2A 单独作为压缩消融。30% 全库压缩目标始终独立报告，不由宽松筛选通过替代。
- 事实质量：G2C 的时间感知行级与语义唯一事实 precision/recall/F1、G1 可见 KG 文本的保守关系投影、当前画像匹配、来源证据覆盖、信息保留和压缩操作日志。G1 KG 投影与 G2C 结构化事实使用不同评分对象，不应直接当作同口径比较。
- 问答：每组 192 道真实 LLM 答案，分别报告冻结规则正确率、匿名 LLM 裁判正确率、核心事实覆盖、证据支持、拒答正确率、状态混淆和上下文 token。G2A 也参与问答，不再只是 retrieval-only 消融。
- 性能与资源：按阶段记录 LLM 次数、失败、token usage 和时延；另外记录检索/embedding 时延、每条时间线的处理时间、可检索语料 token、SQLite/WAL/SHM 占用。
- 完整性：检查来源有效、来源证据覆盖、未来时间线来源、当前状态冲突、问答/盲评配对完整和运行失败。精确事件时间和敏感信息类别只有在样本含相应案例时才有可评估的准确率/阻断率。

本次宽松筛选中普通样本未通过事实质量门槛；高重复样本通过宽松筛选，但系统级全库压缩为 28.5%，仍低于 30% 目标。请以中文对照报告中的完整表格、区间和限制为准。

## 产物与凭证

正式结果目录保存 run-config.json、metrics.json、英文机器报告、compaction-plan/action、模型调用用量、真实问答、检索结果、事实、来源快照和失败日志。运行配置记录模型名、数据哈希、运行版本和 token 策略；API 凭证值不写入结果，不应从本地 LLM 配置读取或复制到报告。

旧的 java-llm-locked_test 和早期 pilot 结果只保留作历史调试，不覆盖本次两个 ablation-v4 正式样本的结果。较早运行存在未来基线数据复制、无完整问答或 KG 侧车修正前版本等问题，不应用于当前组间结论。
