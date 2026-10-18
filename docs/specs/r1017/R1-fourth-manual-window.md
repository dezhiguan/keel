# R1 第四次人工窗口，累计二十五轮

## 目标与授权

2026-10-18 用户明确同意：DF-0017 已用完三次人工窗口和累计二十轮后，把累计上限调到二十五，再开放一组最多五轮的正常页面续修。不重置已有二十次记录，不新建任务，不手改业务仓库，不放宽审批和发布门禁。

## 范围

- 人工窗口由最多三次改为最多四次。每次仍最多五轮。所有来源的沙箱提交累计不超过二十五次。
- 第二十五轮已用完后不能再开窗口。H4 已申请、已合并或运行中的任务仍不能开窗口。失败候选仍须有当前 SHA 的完整沙箱 FAIL 报告。
- 服务端、OpenAPI、元智能体 `maxAttempts` 校验、控制台说明使用同一上限。页面显示已开启次数、累计用量和本次最多新增轮次。

## 修改文件

`contracts/console-api.openapi.yaml`、`console/src/api/schema.d.ts`、`console/src/views/jobs/JobDetail.vue`、`keel-server/.../MetaAgentExecution.java`、`MetaAgentRulesTest.java`、`agents/meta-agent/workflow.py`、`agents/meta-agent/tests/test_repair_loop.py`、`docs/specs/r1014/ui-checklist.md`。

## 验收

- 已开启三次窗口、累计二十轮且当前 SHA 有完整 FAIL 报告时，可以开启第四次窗口，终点为二十五轮。
- 第四次窗口、累计二十五轮、错误报告、运行中、H4 或合并后的任务不能再开。`maxAttempts` 为 26 仍被元智能体拒绝。
- 与任务编号规范化检查同一版部署。部署完成后再从 DF-0017 页面提交反馈。
