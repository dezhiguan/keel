# R1 最后一组修复窗口，问句和命令分开

## 目标与授权

2026-10-19 用户要求继续修复，并明确不要无限增加额度，修复要收敛。DF-0017 已用完五次窗口和累计三十轮。只再开一组最多五轮，累计上限三十五。三十五轮用完后不再提高。

已知误判只有一类：询问是否已经发布、发布到生产的时间或进度，被当成发布命令。肯定的发布命令仍须拒绝。不要为每个新说法再追加一条动词正则。

## 范围

- 人工窗口最多六次。每次仍最多五轮。所有来源的沙箱提交累计不超过三十五次。
- 生成指令写明：审查指出进度问句被误拒时，改已有发布判断，把问句和命令分开。
- 不重置已有三十次记录，不手改业务仓库，不放宽审批和发布门禁。

## 修改文件

`contracts/console-api.openapi.yaml`、`console/src/api/schema.d.ts`、`console/src/views/jobs/JobDetail.vue`、`keel-server/.../MetaAgentExecution.java`、`MetaAgentRulesTest.java`、`agents/meta-agent/workflow.py`、`agents/meta-agent/candidate.py`、`agents/meta-agent/tests/test_repair_loop.py`、`docs/specs/r1014/ui-checklist.md`。

## 验收

- 已开五次窗口、累计三十轮且当前 SHA 有完整沙箱报告时，可以开启第六次窗口，终点为三十五轮。
- 第六次窗口或累计三十五轮后不能再开。`maxAttempts` 为 36 仍被拒绝。
