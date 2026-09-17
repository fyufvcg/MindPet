# 框架验证记录（不是检索实验结果）

日期：2026-09-17。运行环境：Windows PowerShell，Python 3.14.0。

## 实际通过的检查

| 检查 | 实际结果 |
|---|---|
| 5 个 Python 文件的 ast.parse 语法检查 | 全部通过 |
| 3 个 scripts 的 `--help` | 全部退出码 0 |
| 3 个 scripts 的 `--config configs/retrieval.yaml` | 全部退出码 2，明确输出 NOT_IMPLEMENTED |
| 5 个 metric 接口的未实现保护 | 全部抛出 NotImplementedError，没有产生任何分数 |
| 两个 dataset JSONL | 均为 0 字节；未生成实际数据集 |
| results 目录 | 仅有 3 个空 .gitkeep；无原始结果、CSV或图表 |
| Java 实验符号检索 | 没有 RetrievalMode/searchForEvaluation/api/eval；未实施接口 |

帮助入口验证命令：

```powershell
Set-Location D:\MindPet-master\MindPet-Eval
python -B scripts/generate_small_dataset.py --help
python -B scripts/run_retrieval_ablation.py --help
python -B scripts/evaluate_retrieval.py --help
```

执行保护验证命令（**预计报未实现，不是运行实验**）：

```powershell
python -B scripts/generate_small_dataset.py --config configs/retrieval.yaml
python -B scripts/run_retrieval_ablation.py --config configs/retrieval.yaml
python -B scripts/evaluate_retrieval.py --config configs/retrieval.yaml
```

## 没有完成/没有声称通过的检查

- PyYAML 未安装，因此没有用 YAML 库自动解析配置；本轮没有安装 requirements。
- 指标公式尚未实现；保护检查不能代替 Precision/Recall/MRR/nDCG 数学单测。
- Java代码未修改，没有 Java构建、编译或回归测试。
- 没有 HTTP调用、embedding调用、数据库连接、导入或写入。
- 没有真实消融结果，无法判断 Full 与 RRF 的效果差异。

## 文件变更边界

只新增 `docs/memory-retrieval-algorithm.md` 和 `MindPet-Eval/` 文档/框架文件。没有改 `agent-main-flow.md`、运行检查记录、任何业务代码或已有本地配置。

Git检查：根目录与 Java目录都不是 Git仓库；没有擅自初始化Git或创建分支。后续 Java实施前需取得可追溯基线。

当前状态：**框架/方案已准备，等待用户审核，停止实施和实验。**
