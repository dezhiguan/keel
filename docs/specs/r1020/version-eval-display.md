# 版本页展示已落库的评测分和门禁

## 目标

智能体详情的版本表已经列出 `agent_version` 的版本、环境和时间。评测分、门禁按该行自己的记录补上。没有关联记录的行继续留空。

## 取数

- 版本行的 `gate_run_id` 指向 `eval_run.run_id`，且该运行属于同一智能体、状态为 `DONE` 时，用 `result.scoreTotal` 和 `result.passed`。
- 版本行没有可用评测时，用同一智能体、同一版本、同一环境、`kind=release` 的最新发布记录。`ci_run_url` 以 `ev_` 开头时再读对应评测；否则只用记录上的 `gate_passed` 和 `score_total`。
- 回滚记录不参与。不按分数自行推算门禁，也不把智能体最近一次评测贴到旧版本上。
- 「变更」没有落库字段，本轮不生成说明。

## 文件

`AgentDetail.VersionRow`、`VersionEvidence`、`AgentRegistryService` 的版本列表组装，以及对应测试。契约字段已有 `scoreTotal` 和 `gatePassed`，不改接口形状。
