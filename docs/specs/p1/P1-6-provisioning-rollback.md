# P1-6 资源开通编排与失败回滚

## 目标

`POST /api/v1/agents` 按固定顺序开通外部资源。任意一步失败后，已经开通的资源按相反顺序回收，`agent_resource` 里不留下 ACTIVE 记录。

## 依赖

- 前置任务：P1-4、P1-5
- 依赖的契约文件：`contracts/console-api.openapi.yaml` 的 `RegisterRequest`、`SelfCheckReport`；`contracts/error-codes.yaml`
- 依赖的外部组件：本任务不直接调用它们。编排依赖下面三个接口，实现分别在 P1-7、P1-8。测试用假实现。

```
AuthClientProvisioner.provision / revoke
LiteLlmProvisioner.provision / revoke
LangfuseProvisioner.importDataset / revoke
SecretWriter.write / delete
```

## 改哪些文件

```
keel-server/src/main/java/com/keel/server/provisioning/ProvisioningService.java
keel-server/src/main/java/com/keel/server/registry/service/LifecycleService.java
keel-server/src/main/java/com/keel/server/registry/controller/AgentController.java
keel-server/src/test/java/com/keel/server/provisioning/**
docs/specs/p1/P1-6-provisioning-rollback.md
```

`AuthClientProvisioner`、`LiteLlmProvisioner`、`LangfuseProvisioner`、`SecretWriter`、`AgentJwksController` 本任务只保留可注入的接口或空实现，具体协议在 P1-7、P1-8。

## 接口契约

```
POST /api/v1/agents
```

成功返回 `SelfCheckReport`。名称冲突返回 409。

开通顺序（技术文档第 08 节）：

1. `ManifestValidator` 通过后写 `agent`、`agent_version`
2. 生成密钥并登记公钥（接口调用，P1-7 实现）
3. 注册 OAuth 客户端
4. 建薄网关虚拟 Key
5. 写 Secret `keel-{name}`
6. 导入 Langfuse 数据集
7. 跑 `SelfCheckService`
8. 状态置 `REGISTERED`，写一条 `route_snapshot`，写 `config.change` 审计

每一步成功都插入 `agent_resource`（`type` 为 `oauth_client` / `litellm_key` / `secret` / `dataset` / `langfuse`，`status=ACTIVE`）。

## 实现要点

- **失败按已成功步骤的逆序回收。** 第 5 步失败就要删掉第 4 步的 Key、第 3 步的客户端、第 2 步的公钥，并把对应 `agent_resource.status` 改成已回收。回收本身失败要记在资源行上并抛错，不能假装已经干净。
- **重试必须可重复调用。** 同名注册在回滚之后再次进入，不能因为「客户端已存在」卡死。幂等由 P1-7、P1-8 的实现保证，本任务的测试要覆盖「第二步已成功、第三步失败、再次注册」。
- **高风险的 `config.change` 同步写审计。** 写失败则注册失败，不允许改成异步。审计客户端走 `integration/audit`，队列和哈希链不在本任务。
- **不要在编排类里写薄网关或 auth-gateway 的 URL、字段名。** 那些容易写错的细节留在 P1-7、P1-8。
- `route_snapshot` 只追加版本号，不推送网关。网关长轮询是 P2-9。
- 自检失败时状态停在已登记但报告 `passed=false`，已开通的资源不回收（技术文档：不通过则停在 REGISTERED）。这和中途异常不同，不要走回滚。

## 验收标准

```bash
mvn -o -pl :keel-server test
```

- [ ] 四步假实现里任意一步抛错，前面步骤的 `revoke` 按逆序各被调用一次
- [ ] 回滚结束后不存在 `status=ACTIVE` 的 `agent_resource`
- [ ] 自检失败不回滚，响应里的 `SelfCheckReport.passed` 为 false
- [ ] 审计写入失败时 `POST /api/v1/agents` 失败，库中没有半截 ACTIVE 资源
- [ ] 测试不连接真实的薄网关、Langfuse、auth-gateway

## 下线

`POST /api/v1/agents/{name}/retire` 请求体必填 `env`。先同步写 `agent.retire` 审计，再按开通的逆序回收该环境仍为 ACTIVE 的外部资源，状态置 `RETIRED`，并追加一行 `route_snapshot`。没有开通记录的步骤跳过。回收失败把该资源标成 `REVOKE_FAILED` 并中止，状态保持原样。已经是 `RETIRED` 的再次调用直接返回。历史版本、追踪、评测不删。不停止 Deployment，那一步等实例探测接上。

## 明确不做

- 不实现 RSA、换票、虚拟 Key、数据集导入的真实 HTTP（P1-7、P1-8）
- 不实现发布（`POST /releases` 是 P2-11）
- 不实现网关路由推送
