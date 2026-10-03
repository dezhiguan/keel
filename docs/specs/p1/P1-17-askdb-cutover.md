# P1-17 askdb 接入

## 目标

askdb 的线上流量同时进 Langfuse 和 keel-audit。影子运行一周后，链路数、审计条数、评测分数与旧实现一致，然后再删除 askdb 里重复的底座代码。

## 依赖

- 前置任务：P0-6、P0-7、P0-8。评测导入命令如果还没有 `keel eval import`，本任务只把用例文件交到 Langfuse 数据集 `askdb/nl2sql`，不在这里实现完整的 `keel eval`（P2-4）。
- 依赖的契约文件：`contracts/manifest.schema.json`、`contracts/trace-attributes.md`、`contracts/audit-event.schema.json`
- 依赖的外部组件：askdb 仓库（不在本仓库）。Langfuse dev/staging 项目（P1-1）、LiteLLM（P1-2）。

## 改哪些文件

本仓库默认不改。动手文件都在 askdb 仓库，按技术文档第 09 节：

```
askdb/trace.py · observe.py          删除（影子对比通过之后）
askdb/audit.py · auditstore.py       删除（同上）
askdb/quota.py · approvals.py        删除（同上）
askdb/evalstore.py · evalrun.py · evalrunstore.py · evals/   删除（同上）
askdb/llm.py · identity.py · auth.py · mcp_server.py · server.py   改造
agentgraph.py · multiagent/ · planner.py   保留
```

影子对比的记录写在 askdb 仓库的迁移说明里，不写进 Keel 的业务代码。

## 接口契约

- `mount_to` 挂上 `/v1/invoke`，原有 HTTP 接口在切换完成前保留
- LangGraph 使用 `keel.tracing.langgraph` 回调
- 内部子智能体（router、verifier、synthesizer）不单独注册，span 类型仍是 `agent`
- `OK_STATUSES` / `SOFT_STATUSES` 映射到 `keel.status` 的三档，不新增状态
- 审计白名单是现在 `SUMMARY_FIELDS` 的内容，写进 manifest `audit.captureFields`
- 模型调用走 LiteLLM，仓库里不留厂商 Key

## 实现要点

- **先并行，后删除。** 一周内旧追踪、旧审计、旧评测继续写。对比三项都一致之前，不删旧文件。
- **对比的是数量和分数，不是逐字相同的 span 名。** 事先写明：链路数按 trace_id 去重，审计按 action 计数，评测看数据集上的同一批分数。阈值写在迁移说明里，不要事后改口径。
- **审批规则「敏感表需审批」迁到 keel-server 的 `approval_policy`。** 在 P3-1 之前，askdb 如果还必须拦住敏感表，保留调用 SDK 挂起的路径，不要把 `approvals.py` 的业务判断再抄一份新文件。
- **历史审计导出归档后再停旧库。** 不要只停写入就丢历史。
- 身份改为 auth-gateway JWT 时，数据源行级权限逻辑留在 askdb。

## 验收标准

```bash
# 在 askdb 仓库，影子期间旧测试仍要过
pytest
# 对比命令按迁移说明里写死的查询执行，一周结束后：
# 新 trace 数 = 旧 trace 数（允许事先写明的误差）
# 新审计条数 = 旧审计条数
# nl2sql 数据集分数与旧 eval 一致
```

- [ ] 影子周的三组成对数字记录在迁移说明里
- [ ] 删除旧文件的提交发生在对比通过之后
- [ ] askdb 仓库里搜不到厂商 API Key
- [ ] 一条真实问题的 trace 里能看到 router、verifier、synthesizer 的 agent span，且 trace_id 相同

## 明确不做

- 不在本任务改造 offshore-wind、careermate（P2-5、P2-13）
- 不把 askdb 的业务图重写成别的框架
- 不在对比通过前合并删除旧代码的改动
