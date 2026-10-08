# DF-9d 控制台：审计中心按研发任务事件筛选

## 目标

审计中心现有的筛选、详情抽屉、哈希链校验不变。动作选 `config.change` 时多一个"事件类型"筛选，可选 `devflow.stage`（研发任务阶段变化）、`devflow.takeover`（人工接管 / 交还）、`drift`（提示词漂移），按审计 payload 里的 `kind` 过滤。

来源：`docs/console/Keel-全流程智能体化控制台.html` 审计中心增强；`docs/design/Keel-全流程智能体化方案.html` 第 06 节"不新增 action 取值，payload 带 kind"。

## 依赖

- 前置：现有审计中心（`GET /audit/events`）。
- 契约：`contracts/console-api.openapi.yaml` 的 `GET /audit/events`；`contracts/audit-event.schema.json` 不改（`kind` 在 payload 里，受 `captureFields` 白名单约束）。
- 外部：研发任务账本（DF-2）写这类审计之前，真实数据里没有 devflow 事件。

## 改哪些文件

```
docs/specs/devflow/DF-9d-audit.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/audit/AuditView.vue
console/src/views/audit/auditDrawer.ts
console/src/views/audit/auditDrawer.test.ts
console/src/mocks/data/audit.ts
console/src/mocks/handlers.ts
```

本切片只做前端：服务端按 `kind` 过滤归 DF-2。前端对返回的当前页再按 `payload.kind` 收一次，代码里留 `TODO(DF-2)`。

## 接口契约

`GET /audit/events` 加可选查询参数 `kind`（string），只在 `action=config.change` 时有意义，按 `payload.kind` 等值过滤。不升版本。

## 实现要点

- "事件类型"筛选只在动作选 `config.change` 时出现；切到别的动作时清空并隐藏。
- 列表行上，`payload.kind` 存在时在动作旁显示它（等宽字体）。
- 服务端未实现过滤时，前端对返回的当前页再按 `payload.kind` 收一次，并在筛选旁注明"当前页内过滤"。
- mock 补三条样例：`run.suspend`（DF-0021 等人工 review）、`config.change` + `devflow.takeover`（DF-0015 接管）、`config.change` + `devflow.stage`（DF-0020 SPEC → H1）。
- 审计应用账号只有 INSERT / SELECT，本任务不出现任何 UPDATE / DELETE 语句。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/audit/auditDrawer.test.ts
```

- [ ] 不选事件类型时，列表与原来一致
- [ ] 动作 = `config.change` + 事件类型 = `devflow.takeover` 只显示接管事件
- [ ] 切换到其他动作时事件类型筛选消失

## 明确不做

- 新增审计 action 取值
- 写 devflow 审计事件本身（DF-2）
