# P1-22 线上智能体生命周期验收与修复

## 目标

用控制台真实登录验证代码开发智能体登记、语言与工具配置、提示词、评测、调试、追踪、调用量、人民币成本、下线和历史保留。修复实际复现的阻塞；不得把历史查询冒充评测运行，不得让资源已回收的智能体继续借用其他智能体凭据调用。

## 已复现

- 2026-10-13，`qa-lifecycle-1013` 选择 Python、echo、git.pr.diff，登记因 Langfuse 连接超时返回 500，OAuth / key / secret 已回收。
- 该智能体仍能调试成功：共用 echo endpoint，但运行进程读取 echo Secret，独立预算无效。
- 评测 start 实现只查询 latest，没有实际执行评测。
- 数据机 Langfuse relay 的 systemd 服务因旧手动进程占用 443 持续重启。

## 范围

- `docs/specs/p1/P1-22-lifecycle-qa.md`
- `docs/qa/**`
- `keel-server/src/main/java/com/keel/server/{registry,provisioning,insight,integration}/**`
- 对应 Java 测试
- `contracts/manifest.schema.json`、Java/Python 生成模型（可选 runtime.template 字段）
- `contracts/console-api.openapi.yaml` 与生成的前端类型（仅必要的可选字段）
- `console/src/{api,views/agents,views/eval}/**`
- `console/src/utils/format.ts`（保留微额真实成本，区分零值与缺失值）
- `agents/echo/**`、`sdk-python/keel/**` 及对应测试（必要时修复共享运行服务的凭据隔离）
- `deploy/**`（Langfuse relay 的稳定运行和实际测试智能体部署）

## 原则

- 代码开发登记是登记已有服务，非 echo 模板必须可填写真实 endpoint；不把占位地址当作完成部署。
- 调试使用登记环境，拒绝已下线或凭据已回收的智能体；独立运行服务使用自己的 Secret。
- 账单以薄网关为准；将实际请求数和账单、trace 对照，不把未获取的数据当 0。
- 保留测试智能体及证据，清理仅限本次创建的测试资源；用户已有智能体和历史数据不删除。
- 外部接口按 contracts 和 `.cursor/rules/external-apis.mdc` 核实；未知行为不猜测。

## 验收

- 可复现测试记录包含请求结果、trace id、成本和调用量、通过/失败/未验证区分。
- 修复项通过有针对性的单元测试、必要的构建门禁，部署后再次验证。
- 下线后不得继续消耗模型预算；历史追踪仍可查询。
