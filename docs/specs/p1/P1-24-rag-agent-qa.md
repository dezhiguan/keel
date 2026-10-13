# P1-24 知识库智能体验收

## 目标

创建独立 dev / Python / chat-rag 智能体，读取当前账号有权访问的现有知识库，核对检索、带来源回答、追踪与费用。不修改知识库内容，不通过平台提权绕过访问控制。

## 已核实问题

- RAGForge `ApiKeyInterceptor` 默认读取 `X-API-Key`；Keel KnowledgeClient 误用 `Authorization: Bearer`。
- chat-rag 模板固定搜索 demo-kb，检索片段未传入模型，提示词文件未使用，可能生成无依据答案。
- 当前用户可登录 Keel，但 RAGForge 登录返回 403 / RAGFORGE_ACCESS_DENIED；真实检索验收须等待该用户获准访问，或提供目标库的只读 API Key。
- Keel 注册流程没有自动创建 RAGForge API Key；使用知识库须显式向该智能体的 K8s Secret 配置 `KEEL_RAGFORGE_URL`、`KEEL_RAGFORGE_TOKEN`。不得复用其他用户的凭据。

## 实现范围

- `sdk-python/keel/knowledge.py`：使用服务真实的 API Key 请求头。
- `sdk-python/tests/integration/test_clients.py`：通过默认客户端构造验证真实认证头。
- `templates/chat-rag/app.py`、`agent.yaml`、`prompts/answer.md`：使用 manifest 声明的知识库，将片段作为不可信参考资料传入提示词；无结果直接说明缺乏依据；保留引用标识。
- `templates/chat-rag/tests/`：验证片段进入模型、无结果不调用模型、引用保留及检索错误不生成答案。
- `docs/qa/`：记录权限阻断和真实验证结果，未执行项不标通过。

## 验收

1. 先列出可读知识库，按名称和描述选择普通技术资料；检索始终指定 kbIds。独立只读 Key 限定目标库，优先使用已发现的数字 ID，避免服务账号名称解析权限差异。
2. 控制台注册新智能体，显式注入其自身知识库凭据，运行检索与回答。
3. 模型输入确实包含命中片段，输出包含有效来源；无依据不编造答案。
4. Langfuse 中检索与 generation 同 trace；核对分段节点、实际调用和费用。
5. 验收完成下线测试实例并撤销测试 Key，保留证据与历史记录。
