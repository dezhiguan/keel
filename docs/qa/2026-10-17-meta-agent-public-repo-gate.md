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

首次复测时 `DF-0002` 为 `WAITING_REVIEW`。需在人工评审和 staging 资源开通后从可信环境运行门禁；不得将 GitHub Actions 的失败当作通过，或由 PR 作者批准自己的 PR。

## 2026-10-17 续测：预检与 staging 门禁分离

按 `R3-5l` 将公开候选 PR 的 GitHub Actions 改为无密钥预检，不再在未注册的候选服务上提前运行受管 staging 门禁。第 7 次提交 `9ecb4556…` 的预检成功，但沙箱 `SB-0012` 暴露平台生成的冒烟测试把目录 `repo` 错认作智能体名。修复模板断言后，第 8 次精确提交 `17471f83317d0c9801132748df640aa24aa53e04` 的沙箱 `SB-0013` 4/4 通过；该 SHA 的 GitHub Actions `candidate-preflight` 两次运行均成功（`39005151109`、`39005154973`）。主仓库 CI/CD `39004656983` / `39004656943` 均成功，新镜像已部署。

PR `#1` 已从 draft 转为 ready for review。分支保护仍为 `ENFORCED`，批准者为空，任务仍是 `WAITING_REVIEW`。这次 Actions 成功仅证明无密钥预检，**不是** staging 可信门禁通过。候选岗位的正常成功路径仍返回固定决策文本，而 28 条可见用例有不同的期望结论；在真人代码评审和真正门禁前不能合并或发布。公开仓库 PR 不接收平台长期密钥；staging 门禁应在评审和资源开通后的可信环境运行。

两次交付重试期间，`git-ci` 单副本滚动导致短暂连接拒绝；重试在同一修正轮次成功，未复用旧沙箱结果。
