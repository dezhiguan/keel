# P1-11 keel-audit：写入与哈希链

## 目标

高风险审计可以同步写入。其余审计经 RocketMQ 按智能体顺序写入。每条事件接上该智能体的哈希链。并发写链头结果正确，断链能被查出来。

## 依赖

- 前置任务：P0-1（`contracts/audit-event.schema.json`）
- 依赖的契约文件：`contracts/audit-event.schema.json`、`contracts/error-codes.yaml` 的 `AUDIT_WRITE_FAILED`
- 依赖的外部组件：PostgreSQL（库 `keel_audit`，不是 `keel`）、RocketMQ topic `keel-audit-events`。测试用 Testcontainers。本机无 Docker 时跳过，不改用 H2。

## 改哪些文件

```
keel-audit/src/main/java/com/keel/audit/ingest/**
keel-audit/src/main/java/com/keel/audit/chain/**
keel-audit/src/main/java/com/keel/audit/store/AuditEventMapper.java
keel-audit/src/main/resources/db/migration/V1__audit.sql
keel-audit/src/test/java/com/keel/audit/chain/**
keel-audit/pom.xml
docs/specs/p1/P1-11-audit-chain.md
```

`masking/`、`query/`、`ArchiveJob` 保持空类（P1-12）。

## 接口契约

```
POST /api/v1/audit/events
```

请求体通过 `audit-event.schema.json`。同步写入失败时调用方收到 `AUDIT_WRITE_FAILED`，HTTP 503。

异步：topic `keel-audit-events`，按 agent 选择队列，保证同一智能体的消费顺序。

表：`audit_event`（按月分区）、`audit_chain_head`。`audit_archive`、`audit_export` 留给 P1-12，可以在同一条迁移里建空表，但本任务不写它们。

`hash = sha256(prev_hash + 规范化 JSON)`。链头 `SELECT … FOR UPDATE`。

## 实现要点

- **`audit_event` 上不允许 UPDATE / DELETE。** 应用账号 `keel_audit_app` 对这张表只有 INSERT、SELECT。链头在另一张表 `audit_chain_head`，这张表需要 UPDATE。不要把 REVOKE UPDATE 下到整个 schema，否则链头挪不动；也不要为了省事给应用账号放开 `audit_event` 的 UPDATE。
- **链头锁必须在插入之前。** 同一智能体并发两条写入，`prev_hash` 必须串成一条链，不能算出两个相同的 `prev_hash`。
- **同步与异步共用 `HashChainService`。** 不要复制一份「无锁的快路径」。
- **消费失败不要跳过一条再继续。** 跳过会造成断链。失败重试这一条，超过次数进死信并告警，链停在最后一条成功的事件上。
- **规范化 JSON 的字段顺序要固定。** 测试用同一份事件算出稳定 hash。换 JSON 库时不要靠 Map 的遍历顺序。
- `chain/` 的分支覆盖率 100%，没有例外。覆盖率只卡这个包的新增代码。
- 分区键用事件时间的月份。插入旧月份要落到对应分区，而不是报错后丢弃。

## 验收标准

```bash
mvn -o -pl :keel-audit test
```

- [ ] 同一 agent 并发插入，链上每条 `prev_hash` 等于上一条 `hash`，链头 `count` 等于条数
- [ ] 篡改中间一条的 `payload_json` 后，校验能指出断点的 `event_id`
- [ ] 同步写入失败返回 `AUDIT_WRITE_FAILED`，没有半截链头
- [ ] 顺序消息测试：同一 agent 的两条异步事件按发送顺序入链
- [ ] 测试或迁移脚本里不出现 `UPDATE audit_event`、`DELETE FROM audit_event`
- [ ] JaCoCo 报告里 `com.keel.audit.chain` 分支覆盖 100%

## 明确不做

- 不按 manifest 裁剪字段、不做 PII 脱敏（P1-12）
- 不做查询接口、导出、归档 detach（P1-12）
- 不改 `keel` 库的 Flyway（那是 keel-server）
