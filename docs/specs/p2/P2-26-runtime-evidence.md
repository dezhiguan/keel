# P2-26 共享工具、记忆写入和要素运行证据

## 目标

已授权的低风险只读工具 `shift.policy` 能被智能体服务身份执行，并写入控制台审计。服务身份可以写入自己的用户记忆。详情页的要素检查在已有真实记录时标为通过，没有记录时仍保持待验证。

## 依赖

- 前置：D0-4 服务身份、P2-22 工具授权、R1-8 要素检查。
- 契约不改。`shift.policy` 的入参仍是已登记的 `category`。
- 不新增外部接口。审计仍写 `console_audit_event`，不把记忆正文放进 payload。

## 改哪些文件

```
docs/specs/p2/P2-26-runtime-evidence.md
keel-server/src/main/java/com/keel/server/tool/ShiftPolicy.java
keel-server/src/main/java/com/keel/server/tool/controller/ToolInvokeController.java
keel-server/src/main/java/com/keel/server/memory/MemoryService.java
keel-server/src/main/java/com/keel/server/registry/model/dto/AgentElements.java
keel-server/src/main/java/com/keel/server/registry/model/dto/AgentDetail.java
keel-server/src/main/java/com/keel/server/registry/service/ElementEvidence.java
keel-server/src/main/java/com/keel/server/registry/service/AgentRegistryService.java
keel-server/src/test/java/com/keel/server/tool/ShiftPolicyTest.java
keel-server/src/test/java/com/keel/server/auth/ServiceIdentityFilterTest.java
keel-server/src/test/java/com/keel/server/registry/model/AgentDetailTest.java
sdk-python/keel/audit/reporter.py
```

## 实现要点

- `git.pr.diff` 仍只转给 git-ci。`shift.policy` 在 manifest 声明为 read/low、授权有效、最新版本也是 READ/LOW 时，按类别返回口径。类别不对返回参数错误，不冒充未授权。
- 要素声明本身仍不是通过。只有允许的工具调用、用户记忆行、通过的回归、审计事件这些已经落库的记录，才把对应项改成通过。没有记录保持待验证或未声明。
- 低风险审计先落本地队列。没有消息队列发布器时再尝试把同一条事件交给 `KEEL_AUDIT_URL`；这次投递失败不让业务失败。高风险仍是同步失败即拒绝。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=ShiftPolicyTest,ServiceIdentityFilterTest,AgentDetailTest,ArchitectureTest test
```

- [ ] `shift.policy` 只接受故障、变更、咨询
- [ ] 服务身份可以访问 `POST /api/v1/agents/{name}/memories`
- [ ] 没有运行记录时，已声明的工具调用仍是待验证；有记录时该项为通过

## 明确不做

- 不把未授权工具放行
- 不因为 manifest 写了 capabilities 就把要素判成通过
- 不在本任务里重建智能体镜像
