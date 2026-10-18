# R1 第五次人工窗口，累计三十轮

## 目标与授权

2026-10-18 用户明确同意：DF-0017 已用完四次人工窗口和累计二十五轮，自动审查仍有 app.py 非字符串输入和目录字段类型两条意见。把累计上限调到三十，再开放一组最多五轮，只修这两条。不重置已有记录，不手改业务仓库，不放宽审批和发布门禁。

## 范围

- 人工窗口由最多四次改为最多五次。每次仍最多五轮。所有来源的沙箱提交累计不超过三十次。
- 第三十轮用完后不能再开窗口。H4、已合并、运行中、错误报告的任务仍不能开窗口。

## 修改文件

`contracts/console-api.openapi.yaml`、`console/src/api/schema.d.ts`、`console/src/views/jobs/JobDetail.vue`、`keel-server/.../MetaAgentExecution.java`、`MetaAgentRulesTest.java`、`agents/meta-agent/workflow.py`、`agents/meta-agent/tests/test_repair_loop.py`、`docs/specs/r1014/ui-checklist.md`。

## 验收

- 已开启四次窗口、累计二十五轮且当前 SHA 有完整沙箱报告时，可以开启第五次窗口，终点为三十轮。
- 第五次窗口或累计三十轮后不能再开。`maxAttempts` 为 31 仍被拒绝。
