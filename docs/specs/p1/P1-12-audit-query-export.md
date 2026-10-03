# P1-12 keel-audit：脱敏、查询、导出与归档

## 目标

审计 payload 只保留 manifest `audit.captureFields` 里的字段，手机号、身份证、银行卡会被脱敏。控制台能按条件查询、手动校验哈希链。导出先产生审批申请，批准前不生成文件。超过保留期的分区 detach 之后仍然能校验。

## 依赖

- 前置任务：P1-11
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `/audit/events`、`/audit/verify`、`/audit/exports`；`contracts/audit-event.schema.json`
- 依赖的外部组件：对象存储，用于归档文件。审批创建调用 keel-server `POST /api/v1/approvals`，`subject_type=data.export`。该接口在 P3-1 才实现，本任务用假的 keel-server 测契约，不在 keel-audit 里做审批状态机。

## 改哪些文件

```
keel-audit/src/main/java/com/keel/audit/masking/**
keel-audit/src/main/java/com/keel/audit/query/**
keel-audit/src/main/java/com/keel/audit/store/ArchiveJob.java
keel-audit/src/test/java/com/keel/audit/**
docs/specs/p1/P1-12-audit-query-export.md
```

不改 `HashChainService` 的链接算法。脱敏发生在入链之前，入链之后的 payload 就是脱敏结果。

## 接口契约

```
GET  /api/v1/audit/events
POST /api/v1/audit/verify?agent=
POST /api/v1/audit/exports
```

查询参数与 `AuditEvent` 字段以 OpenAPI 为准。校验响应含 `checked`、`intact`、`brokenAt`、`elapsedMs`。

导出返回 202：`exportId`、`approvalId`。`audit_export.status` 在批准前不是完成态，`file_path` 为空。

## 实现要点

- **白名单之外的字段丢掉，不是打码后保留键。** 没有 manifest、拿不到 `captureFields` 时，payload 置空并记一条告警，不要把原文入库。
- **PII 是兜底，不是白名单的替代。** 白名单字段里出现手机号、身份证、银行卡仍要脱敏。规则要有测试向量，不要只写「做了脱敏」。
- **查询和导出都走只有 SELECT 的账号。** 导出文件也是脱敏后的内容。不要为了导出开一个有 UPDATE 权限的账号。
- **归档用 detach，不 DELETE。** detach 之后 `ChainVerifyJob` 仍能用 `audit_archive` 里的 sha256 和对象存储上的文件校验这一段。做不到就不要宣称已归档。
- **导出不在本任务里自动批准。** 假 keel-server 返回 `approvalId` 后，本服务把 `audit_export` 停在等待批准。批准后的生成动作留一个由审批回调触发的方法，测试直接调用它，不实现企业微信。
- 控制台路径是 `/api/v1/audit/*`。如果查询实际由 keel-server 转发，转发只放在 keel-server 的 `integration/audit`，业务逻辑仍在 keel-audit。

## 验收标准

```bash
mvn -o -pl :keel-audit test
```

- [ ] `captureFields: [pr_url]` 时，payload 里没有其他键
- [ ] 白名单字段中的手机号、身份证、银行卡被替换，前后长度或格式符合用例预期
- [ ] `GET /audit/events` 能按 agent、action、risk、traceId、时间过滤并分页
- [ ] 未批准的导出没有 `file_path`
- [ ] detach 一个分区后，校验仍能发现该分区内被改过的文件
- [ ] 源码和测试里没有 `UPDATE audit_event`、`DELETE FROM audit_event`

## 明确不做

- 不实现审批状态机、超时和通知（P3-1、P3-2）
- 不改哈希算法（P1-11）
- 不在控制台去掉 mock（P1-15）
