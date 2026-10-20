# 自检与版本管理修复验收

## 实现

- `a804424cc6959201a97a54151f2b1412322dbb56`：自检清单及五条探针补齐仅 meta-agent 使用的服务认证；401/403 与清单不一致分开报告；没有探针时明确追踪未执行。
- 正式版本与候选草稿分区，v0 与同来源环境更晚的正式快照哈希一致时显示为已归档草稿；不删除历史。来源环境与最新发布/回滚环境分列，同版本跨环境通过评测 ID 和镜像防误关联。
- 保留历史版本自己的评测/门禁读取，去掉当前评分兜底；护栏保存只写候选且清除候选的旧镜像/门禁，无变化不新建候选，正式快照保留。
- `81e225616a68db5ce16a56669ce998b9c3812b3e`：补齐元智能体入口对 self-check 标记及精确 probe 输入的技术响应，避免生成需求、调用模型/工具或挂起人工确认。普通输入及正常评测不走该分支。

## 自动化验证

- 首次提交完整 CI 39365467697 success；CD 中服务端 358 项全部通过，含真实 HTTP 认证和 PostgreSQL 护栏快照保留、草稿及回滚证据测试。该 CD 后被包含本修复的独立渠道配额提交 supersede，未将取消当作部署成功。
- 最新提交完整 CI 39366305236 success。CD 39366305192 的元智能体及相关 SDK 测试四组为 123 / 32 / 23 / 10 项，合计 188 项通过。
- 本地前端针对性 17 项通过，隔离工作目录生产构建通过。沙箱拒绝本机监听端口，Docker 不可用，HTTP/数据库集成测试以远程 CI 的非跳过成功为准。

## 部署与账号页面验收

[最终 CI](https://github.com/dezhiguan/keel/actions/runs/39366305236) 和 [最终 CD](https://github.com/dezhiguan/keel/actions/runs/39366305192) 均 success。部署镜像为 `81e225616a68`；keel-server、keel-console、meta-agent 都为 2/2。

实际使用 guandezhi（userId 54，官德志，USER，可写）的正常浏览器会话，点击详情页重新自检、版本页、运行回归、发布到 prod 并确认发布。未使用其他账号代替。

- 首次页面自检通过，追踪 `b4a8c5e98190653a01d5820062237952` 查询成功，状态 ok、16ms，只有 self-check、guard.input、agent.run 三个节点，没有 generation 或 tool。
- 两个实际实例分别直接调用技术探针，均 HTTP 200，事件 `guardrail → step → final`，答案 `probe ok`；无 tool/token/suspend/error。无服务身份读取 manifest 仍 401。
- 实例为 `meta-agent-6ff4c9b456-r97mz` / `meta-agent-6ff4c9b456-wllhh`，均 Ready、重启 0，镜像摘要均 `sha256:f9323f55ed16ed31fa3dabdf75eef436e1cc5421f96c9eb65dddaa52198e4f26`。
- 页面正式版本表不再混入 v0；明确“已归档 1 份已生成正式版本的草稿”，来源环境和当前发布环境分别展示，历史分数/门禁均来自各自证据。

## 完整评测与发布

第一轮 `ev_99290d98f2aa490580120e5ebba51bd7` FAILED，错误为 `IncompleteExperiment`。运行日志显示 Item 54 的一次模型生成未满足岗位 `prompt.compile()` 约束，经过既有候选校验仍被拒绝；该轮没有发布。追踪列表还出现过 429，但不是上述失败的日志原因。保留该失败，不把部分成功当作完整通过。

没有修改代码、题集、评分器、提示词或门槛，对同一镜像复测：`ev_2a4353783d6f4fed8160f1796c005a97` DONE，complete=true、passed=true，55 条、总分 1.00，19 个维度全部 deltaPt=0，无新增失败。门槛保持 0.99 / 2pt，实际提示词 draft3、revise1、generate2、review1、expand2。该结果证明本轮完整回归通过，不代表模型生成在重复运行中绝不会失败。

在页面以 guandezhi 确认发布 `prod/v4`，绑定通过的评测和 `meta-agent:81e225616a68`。v4 的来源环境仍为 dev，当前发布环境为 prod，符合页面说明。v1/v2/v3 保留各自镜像、分数和门禁；v3 仍绑定原 `d81879d16590` 镜像。清单哈希保持 `a2db03c59b384d78d6f8b5959a2746fd1b4aef4b0d48b16f655b48c9d90ff820`。

发布后再次在页面点击自检，全部通过，协议/追踪均关联新的 `5a0dd0d96cc72f3fa5d281be96b69784`；状态正常、实例 2/2。最终版本表复核 v4 为当前登记版本并发布到 prod，v1～v3 为历史版本。

[脱敏结构化证据](evidence/self-check-version-lifecycle.json)保留完整通过结果、首次失败、版本行、实例及探针摘要，不含密码、认证令牌或用户业务输入。
