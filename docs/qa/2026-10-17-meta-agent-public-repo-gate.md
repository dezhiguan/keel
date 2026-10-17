# 元智能体候选评分与公开仓库复测

日期：2026-10-17。任务：`DF-0002`，候选仓库：`dezhiguan/spec-agent`，PR：`#1`。

## 两个原阻塞的结果

| 项目 | 线上结果 |
| --- | --- |
| 候选评分文件 | 新提交 `14522b513ba4380486d14d73bf065952bec60fd3` 的 `evals/scorers.py` 包含可执行 `score_decision(expected, answer, tags)`，对期望与实际的 `output.decision` 做归一化精确比较；旧占位注释已替换。 |
| 分支保护 | 将任务仓库转为公开后，`main` 分支保护读回为 `ENFORCED`，要求至少一位批准者；当前 PR 仍为 draft，批准者为空，合并被阻止。 |
| 沙箱 | 修正轮次 1/1 生成新提交，`SB-0011` 对该 SHA 执行 4 个测试，全部通过。任务回到 `WAITING_REVIEW`。 |

公开前检查了仓库全部 6 个可达提交及 Actions 日志，未发现已提交的凭据值。公开仓库支持当前账号套餐的分支保护；这不改变真实人工批准、精确 SHA 和沙箱要求。

## 继续测试发现的阻塞

新提交触发的 GitHub Actions `keel / verify` 运行 `39001182520` 失败。单元测试阶段通过；`Gate` 阶段启动 `uvicorn app:app` 时缺少 `KEEL_LLM_BASE_URL`、`KEEL_LLM_KEY`、`KEEL_AUDIT_URL`、`KEEL_AUDIT_TOKEN`、`KEEL_AUDIT_SPOOL_PATH`、`KEEL_ENV`、`KEEL_SERVICE_AUDIENCE`、`KEEL_CLIENT_PRIVATE_KEY`，服务未监听，`keel gate --env staging` 未执行。因此不能报告可信门禁分数或发布成功。

此外，候选 `role.py` 的常规成功路径仍固定返回“停在 H1 之后的评测关口，不发布”；其 4 个本地测试没有证明 28 条可见用例的分数达到 0.80。评分函数已具备拒绝错误答案的能力，但候选岗位本身尚未经可信门禁验证。

首次修正交付曾因 `git-ci` 连接拒绝失败。服务恢复健康后重试成功，未增加修正轮次。

## 当前状态

`DF-0002` 为 `WAITING_REVIEW`。下一步是为候选 CI 提供受保护的 staging 运行配置，重新运行可信门禁；门禁通过后仍需另一位真实 GitHub 用户完成 PR 审查。不得将 GitHub Actions 的失败当作通过，或由 PR 作者批准自己的 PR。
