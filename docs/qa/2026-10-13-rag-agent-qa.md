# 知识库智能体测试（2026-10-13）

## 当前结论

**真实知识库端到端流程尚未验收通过。** 用户授权账号可登录 Keel，但在 RAGForge `/api/auth/login` 返回 HTTP 403 / `RAGFORGE_ACCESS_DENIED`：请先在 RAGForge 注册或由管理员开通访问权限。

未取得目标知识库列表和只读凭据，因此未创建带虚假知识库配置的智能体、未检索任何私有资料、未使用其他账号或管理员提权绕过权限。待账号获准访问，或提供仅授权目标库的只读 API Key 和库名称后，继续创建独立测试智能体并验证真实检索、带来源回答、追踪、费用及下线。

## 发现并修复

1. `KnowledgeClient` 错把 RAGForge API Key 放在 Bearer 头；按当前后端 `ApiKeyInterceptor` 与 `ApiKeyProperties` 改为 `X-API-Key`。
2. chat-rag 模板固定检索 `demo-kb`；改为读取 manifest 中声明的知识库。
3. 模板虽检索但没有向模型传入片段，提示词文件未生效；改为 `ctx.prompt` 编译问题和片段并关联 generation。提示词限定基于资料、标明来源，参考片段不作为指令；无命中直接说明没有依据，不调用模型。

## 已执行验证

```bash
PYTHONPATH=templates/chat-rag:sdk-python .venv/bin/python -m pytest -q \
  sdk-python/tests/integration/test_clients.py templates/chat-rag/tests
```

25 项通过，包括默认客户端真实认证头、指定 kbIds、检索片段进入模型、引用标识保留、无结果不调用模型、检索失败不生成无依据回答。此处为本地自动化验证，不代表现网知识库已经检索成功。

## 接入要求与后续验收

- Keel 现有注册流程不自动创建 RAGForge API Key，须为新智能体自身 Secret 注入 `KEEL_RAGFORGE_URL` 和 `KEEL_RAGFORGE_TOKEN`。
- 先获取可读知识库列表，按名称与描述选择目标；检索显式指定发现的数字 ID。现有名称解析使用的旧列表接口对 SERVICE_ACCOUNT 的授权仍待真实验证。
- 验证控制台注册、检索命中、回答来源、同 trace 的 retriever / generation、RAGForge 分段节点，以及实际模型费用；未执行项保持未通过。
- 测试结束下线独立测试实例并撤销测试 Key，保留历史。

本机证据：`/Users/amy/keel-rag-qa-20261013/access-check.json`、`rag-tests.log`。证据不包含密码或 API Key。设计：[P1-24](../specs/p1/P1-24-rag-agent-qa.md)。
