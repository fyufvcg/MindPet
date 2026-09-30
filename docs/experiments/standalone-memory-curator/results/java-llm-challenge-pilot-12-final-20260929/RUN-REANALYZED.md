# 重算说明

此目录的原 `metrics.json` 是 runner 缺陷修复前的 12 条探索性真实 LLM 试跑输出。其检索汇总因 G1/G2 大小写不一致错误为 0，事实匹配也把生产库 `confirmed` 与金标 `observed`、历史位置谓词别名误作不匹配。

使用 `pilot-reanalysis.json` 与 `pilot-reanalysis.md` 中的修正后结果。原始调用、检索、问答、记忆单元和 SQLite 文件均保留。此运行不是正式结果：它只有每场景 1 条时间线，且两组当时共用隔离数据库的不同用户命名空间。
