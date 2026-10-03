# P0-9 keel-lite：本地版审计与审批

## 目标

`keel dev` 下不连任何后端服务，审计能写进本地 SQLite 并通过哈希链校验，审批能挂起、能在本地批准、批准后执行从挂起处继续。

## 依赖

- 前置任务：P0-1、P0-1a（契约冻结）
- 依赖的契约文件：`audit-event.schema.json`、`sse-events.schema.json`（`suspend` 事件）、`error-codes.yaml`
- 依赖的外部组件及其真实行为：无。keel-lite 的全部意义就是**不依赖任何外部组件**

## 改哪些文件

```
sdk-python/keel/lite/{__init__.py,store.py,audit.py,approval.py,server.py,chain.py}
sdk-python/tests/lite/**
```

## 接口契约

keel-lite 是一个进程内替身，暴露和真实服务**同样的接口形状**，让 SDK 不用区分本地和线上：

```
POST /api/v1/audit/events        # 同步写，等价 keel-audit
POST /api/v1/approvals           # 建审批单
POST /api/v1/approvals/{id}/decision
GET  /api/v1/approvals           # 本地待办列表，keel dev 的终端 UI 用
```

`GET /api/v1/approvals` 的响应形状**直接用 `contracts/console-api.openapi.yaml` 的 `Envelope + PageMeta + Approval`**（含 `subjectType`、`subjectRef`、`runId`），不要另定一套字段。这样以后控制台连 keel-lite 也能看到本地待办，不会出现两套审批单形状。

SQLite 三张表，字段对齐 PostgreSQL 版但只保留必要列：

```
audit_event       event_id, ts, agent, action, resource, risk, decision,
                  payload_json, input_digest, prev_hash, hash
audit_chain_head  agent, last_event_id, last_hash, count
approval_request  id, subject_type, subject_ref, agent_name, run_id,
                  summary, status, decided_by, decided_at
```

## 实现要点

- **哈希链要真的实现，不能简化**。本地跑通但线上断链是最糟的情况——开发期发现不了。`hash = sha256(prev_hash + 规范化 JSON)`，规范化规则**必须和 `contracts/tests/README.md` 里写的完全一致**（P0-3）。keel-lite 要跑同一套 `canonical/` 用例。这一条是 P0-9 的核心价值：它让每个开发者在本地就能碰到链的行为。

- **`risk: high` 同步写失败要拒绝执行业务**，本地也一样。不能因为是本地就降级成"打个日志继续"——那样开发期永远测不出这条铁律的行为，上线才发现业务逻辑没处理这个失败路径。

- **只追加，不给 UPDATE / DELETE 留口子**。真实的 `keel_audit` 库靠数据库账号权限保证（REVOKE UPDATE, DELETE），SQLite 没有这个机制，所以在代码层面钉死：`store.py` 里对 `audit_event` 只提供 insert 和 select 两个方法，不写 update / delete。加一条测试扫 `lite/` 的源码确认没有 `UPDATE audit_event` / `DELETE FROM audit_event` 字样。

- **审批挂起要走和线上一样的路径**（P0-1a）。本地不是"跳过审批直接执行"，而是真的发 `suspend` 事件、真的建审批单、真的等 `resume` 调用。否则 `keel dev` 验证不了智能体代码里的挂起分支，到 staging 才第一次跑——那条路径最容易有 bug。

- **进程重启后挂起中的审批不能丢**（`testing.mdc` 点名要求）。SQLite 本身是文件，审批单天然持久。但 `checkpoint_ref` 指向的现场由智能体自己存（P0-1a 的边界），keel-lite 只负责把 `run_id` 和 `checkpoint_ref` 的对应关系落盘。要有用例：建审批单 → 杀进程 → 重启 → 审批单仍在且能批准。

- **SQLite 并发写要处理**。`keel dev` 可能同时有 HTTP 请求和后台重放两个写入方。用 WAL 模式 + 串行化链头写入（对应 PostgreSQL 版的 `SELECT … FOR UPDATE`）。链头并发写入错误会导致哈希链乱序，这在 `testing.mdc` 里是必须有用例的场景。

- **数据库文件位置要明确且可清理**。放项目下的 `.keel/dev.db`，并写进 `keel new` 生成的 `.gitignore`（P0-10 / P0-13）。审计数据里会有用户原文（白名单字段），不能被误提交。

- keel-lite **不是服务**，是 `keel dev` 起的单进程替身（技术文档第 04 节明确）。不要做成独立部署的东西，不要加认证、不要监听 0.0.0.0。

## 验收标准

```bash
cd sdk-python && pytest tests/lite -q
```

- [ ] `keel dev` 下执行一次带 high 风险动作的调用，审计落进 `.keel/dev.db`
- [ ] 哈希链校验通过；手改一条记录的 `payload_json` 后校验失败并指出断链位置
- [ ] keel-lite 跑 `contracts/tests` 的 `canonical/` 用例，sha256 与 Java 侧一致
- [ ] 假 keel-lite 审计写失败时，high 风险动作被拒绝执行
- [ ] 审批流程走通：收到 `suspend` 事件 → 本地批准 → `resume` 后执行继续 → 审计记 `approval` 和 `tool.call`
- [ ] 建审批单后杀进程、重启，审批单仍在且可批准
- [ ] 并发写入 20 条审计，链头顺序正确、无重复 `prev_hash`
- [ ] 源码扫描：`lite/` 下没有对 `audit_event` 的 UPDATE / DELETE

## 明确不做

- 不做配额、护栏、换票的本地替身（这一期 `keel dev` 下这些直接放行）
- 不做 Web UI。本地待办用 CLI 命令或终端输出，图形界面是控制台的事
- 不做 MQ 异步通道的替身——本地全部同步写，行为差异写进 `keel dev` 的启动提示
- 不做数据迁移和归档
