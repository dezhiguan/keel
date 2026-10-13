# P2-24 新建向导勾选知识库并发放只读密钥

## 目标

代码开发的新建向导第三步按控制台原型列出 rag-forge 知识库。勾选结果写入 `agent.yaml` 的 `spec.knowledge`。注册时用 keel-server Secret 里的代发身份向 rag-forge 申请一把只读密钥，范围只含勾选的库，并写入该智能体自己的 Secret。代发身份不进代码，也不抄进智能体。

## 依赖

- 前置：P2-22 向导共享工具、`RegisterRequest.knowledgeBases`、`GET /catalog` 的 `knowledgeBases`。
- 契约不改。`knowledgeBases` 仍是知识库名字数组。
- 外部组件：rag-forge `GET /api/v1/kb`、`POST /api/v1/keys`、`DELETE /api/v1/keys/{id}`。检索仍用 `X-API-Key`。密钥字段是 `scopeMode=KB_LIST`、`accessLevel=READ`、`allowedKbIds`。调用方必须是这些库所属组织的管理员，库必须属于同一组织。

## 改哪些文件

```
docs/specs/p2/P2-24-wizard-knowledge.md
keel-server/src/main/java/com/keel/server/registry/controller/CatalogController.java
keel-server/src/main/java/com/keel/server/registry/service/WizardCatalog.java
keel-server/src/main/java/com/keel/server/registry/service/WizardKnowledge.java
keel-server/src/main/java/com/keel/server/registry/service/RegisterManifest.java
keel-server/src/main/java/com/keel/server/registry/service/LifecycleService.java
keel-server/src/main/java/com/keel/server/registry/service/AgentRegistryService.java
keel-server/src/main/java/com/keel/server/provisioning/SecretWriter.java
keel-server/src/main/java/com/keel/server/provisioning/RagForgeAccess.java
deploy/k3s/services/keel-server.yaml
keel-server/src/test/java/com/keel/server/registry/service/RegisterManifestTest.java
keel-server/src/test/java/com/keel/server/registry/service/WizardKnowledgeTest.java
keel-server/src/test/java/com/keel/server/provisioning/RagForgeAccessTest.java
console/src/api/agents.ts
console/src/views/agents/wizard.ts
console/src/views/agents/wizard.test.ts
console/src/views/agents/AgentCreateWizard.vue
```

## 实现要点

- 目录里的知识库来自 rag-forge 的 keel 指标（名字和所属组织）。同名只保留第一行。指标不可用时列表为空，不编造原型里的示例名。
- 向导只提交名字。不在目录里的名字拒绝预览和注册。未勾选时 manifest 不写 `knowledge`。
- 勾选非空时，在开通其他资源之前，用代发身份调用 rag-forge：先按名字解析数字 id，再创建 `KB_LIST` + `READ` 密钥。代发身份来自环境变量，由 Secret `keel-server` 注入：`RAGFORGE_PROVISION_TOKEN`，或者 `RAGFORGE_PROVISION_ACCOUNT`、`RAGFORGE_PROVISION_PASSWORD` 加客户端私钥。账号口令走 auth-gateway 登录，`target_aud=ragforge-admin-api`，访问票在进程内缓存到过期前 60 秒。身份没配、库不在该身份可见范围、或 rag-forge 拒绝时，注册失败，不写智能体 Secret，也不回收已经开通的别的资源。
- 新密钥明文只写入 Secret `keel-{agent}` 的 `KEEL_RAGFORGE_TOKEN`，地址写入 `KEEL_RAGFORGE_URL`。不把代发身份、指标账号口令抄进智能体 Secret。
- 开通回滚或下线删除 Secret 时，同时吊销这把 rag-forge 密钥。吊销接口返回 404 视为已经删除。

## 验收标准

```bash
mvn -o -pl :keel-server -Dtest=RegisterManifestTest,WizardKnowledgeTest,RagForgeAccessTest test
cd console && ./node_modules/.bin/vitest related src/views/agents/wizard.test.ts
```

- [ ] 勾选后预览和注册的 manifest 含 `spec.knowledge[].kb`
- [ ] 目录里没有的名字被拒绝
- [ ] 假的 rag-forge 在创建密钥时收到 `KB_LIST`、`READ` 和对应 id，Secret 里能读回 `KEEL_RAGFORGE_TOKEN`
- [ ] 向导第三步从 `/catalog` 渲染知识库

## 明确不做

- 不改「由智能体生产」那条需求表单
- 不把检索接进回声进程；回声模板仍用自己的 Secret
- 不跨组织拼一把密钥，组织约束以 rag-forge 的拒绝为准
