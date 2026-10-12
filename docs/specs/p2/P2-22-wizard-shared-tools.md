# P2-22 新建向导勾选共享工具

## 目标

代码开发的新建向导第三步按控制台原型列出共享工具。勾选结果写入注册用的 `agent.yaml`，并给这个智能体授权，使工具页能把它算成依赖方。

## 依赖

- 前置：工具注册表（`tool` / `tool_version`）、`POST /tools/{name}/grants`、`RegisterRequest.tools` 与 `GET /catalog`。
- 契约不改。`tools` 仍是工具名数组，`approvalPolicyId` 仍是可选字符串。
- 外部组件：无。

## 改哪些文件

```
docs/specs/p2/P2-22-wizard-shared-tools.md
keel-server/src/main/java/com/keel/server/registry/controller/CatalogController.java
keel-server/src/main/java/com/keel/server/registry/service/WizardTools.java
keel-server/src/main/java/com/keel/server/registry/service/WizardCatalog.java
keel-server/src/main/java/com/keel/server/registry/service/RegisterManifest.java
keel-server/src/main/java/com/keel/server/registry/service/LifecycleService.java
keel-server/src/main/java/com/keel/server/registry/service/AgentRegistryService.java
keel-server/src/test/java/com/keel/server/registry/service/RegisterManifestTest.java
keel-server/src/test/java/com/keel/server/registry/service/WizardToolsTest.java
keel-server/src/test/java/com/keel/server/insight/CostServiceTest.java
console/src/api/agents.ts
console/src/views/agents/wizard.ts
console/src/views/agents/wizard.test.ts
console/src/views/agents/AgentCreateWizard.vue
```

## 实现要点

- `GET /catalog` 的 `tools` 返回范围是共享、且未下线的工具，带最新版本的读写和风险。`approvalPolicies` 返回 `subject_type=tool.call` 的策略。知识库仍为空。
- 向导只提交工具名。服务端按注册表填写 `access`、`risk`。高风险写入 `approval: required`，并且必须带一个已存在的审批策略编号。不改共享工具自己的审批策略。
- 名字不在共享注册表里、或已下线，拒绝注册。
- 智能体行写入之后，用当前控制台用户给每个勾选的工具写 `agent_tool_grant`，版本范围用该工具当前版本。
- 预览 YAML 与注册用同一份解析结果，预览不写授权。
- 未勾选工具时，manifest 不写 `tools`，和现在的回声注册一致。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=RegisterManifestTest,WizardToolsTest test
cd console && npx vitest related src/views/agents/wizard.test.ts
```

- [ ] 勾选低风险共享工具后，预览和注册的 manifest 含 `name`、`access`、`risk`
- [ ] 高风险工具没有审批策略时拒绝
- [ ] 私有、已下线、不存在的名字拒绝
- [ ] 向导第三步从 `/catalog` 渲染共享工具，高风险时审批策略必填

## 明确不做

- 不在向导里勾选知识库、降级模型
- 不改「由智能体生产」那条需求表单
- 不把共享工具调用接进智能体进程；声明和授权写上之后，调用仍由智能体代码发起
