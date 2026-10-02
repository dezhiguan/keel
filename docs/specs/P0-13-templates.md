# P0-13 五个脚手架模板

## 目标

`keel new --template X` 生成的五种项目，每个都能直接 `keel dev` 起来、自带 ≥ 10 条冒烟用例且全部通过。

## 依赖

- 前置任务：P0-10（CLI `new` / `dev`）、P0-6~P0-8（Python SDK）、P0-11（Java starter）
- 依赖的契约文件：`manifest.schema.json`
- 依赖的外部组件及其真实行为：模板里不允许出现任何真实端点和密钥

## 改哪些文件

```
templates/chat-rag/**
templates/tool-agent/**
templates/graph-agent/**
templates/supervisor/**
templates/java-spring/**
sdk-python/tests/templates/test_all_templates.py
```

## 接口契约

五个模板各自的定位：

| 模板 | 语言 | 演示什么 | 最小依赖 |
|---|---|---|---|
| `chat-rag` | Python | `ctx.knowledge` + `ctx.llm`，带 citations | rag-forge |
| `tool-agent` | Python | `@agent.tool`，含一个 `risk: high` 工具走审批挂起 | keel-lite |
| `graph-agent` | Python | LangGraph 多步，演示 checkpoint 与恢复 | LangGraph |
| `supervisor` | Python | `ctx.delegate` 委派两个子智能体并行 | 另两个本地 agent |
| `java-spring` | Java | `@KeelAgent` + starter，一个进程两个 agent | starter |

每个模板的结构按 `agent-manifest.mdc`：

```
{agent}/
├── agent.yaml  app.py  tools/  prompts/
├── evals/{seed.jsonl,scorers.py}
├── tests/  Dockerfile  .github/workflows/keel.yml  .gitignore
```

## 实现要点

- **模板是大多数人对 Keel 的第一印象，也是他们抄代码的来源**。模板里写错的东西会被复制到十个智能体里。所以模板的审查标准比普通代码高：铁律里的每一条，模板都必须是正面示范，不能有"演示用先这样写"的妥协。

- **模板里绝对不能出现厂商 API Key，哪怕是假的**。不要写 `api_key = "sk-xxx"` 这种占位符——它会被人直接替换成真 key 提交上去。模型配置只出现 LiteLLM 的别名，密钥从环境变量读且无默认值。P2-2 的静态扫描会扫这个，模板必须能通过。

- **`tool-agent` 必须演示审批挂起的完整路径**（P0-1a）。一个 `risk: high` 的工具，调用时收到 `suspend` 事件、本地批准、`resume` 后继续。这是最容易写错的业务分支，模板里给出正确写法比写十页文档有用。`agent.yaml` 里 `risk: high` 必须配 `approval: required`，否则注册自检不过。

- **`graph-agent` 的 checkpoint 是 askdb 迁移的参照**。它要演示「底座管账、智能体管现场」这个边界：现场怎么序列化、`checkpoint_ref` 怎么交给底座、恢复时怎么还原。P1-17 迁 askdb 时会照着这个改。

- **`supervisor` 的 `delegates` 必须在 manifest 里声明**，没声明的不允许委派。模板要演示预算 `X-Keel-Budget` 向下传递——子智能体花掉的要从父的额度里扣。

- **冒烟用例要真的能跑，不是占位**。每个模板 ≥ 10 条，放 `tests/`，`keel dev` 起来后能全绿。用例本身也是示范：教人怎么测一个智能体。

- **`evals/seed.jsonl` 不要凑数**。注册时它会被导入 Langfuse 数据集，门禁按 tags 逐维度对比。模板里给 3～5 条**带 tags 的真实形状**的例子，并在注释里写明「正式接入要 ≥ 50 条，覆盖 manifest 里所有 tags」——不要给 50 条假数据让人以为够了。

- **`Dockerfile` 基于 `keel/python-runtime`**（技术文档第 05 节）。这个基础镜像这一期可能还不存在，**待确认**：是先建基础镜像还是模板里先用官方 python 镜像加注释。**开工前必须先由技术负责人定下来并写回这里**，不要由执行者自己选——五个模板都会被复制到十个智能体里，事后改要改十处。

- **`.github/workflows/keel.yml` 这一期只放占位**。正式的 CI 模板是 P2-3，内容是 `pytest → keel gate → build → keel release`。现在 `keel gate` 和 `keel release` 都没有，模板里先只放 `pytest` 并注释说明后续会补。

- 五个模板共用的部分（`.gitignore`、`Dockerfile` 骨架）会重复。按 `AGENTS.md`「先写重复代码，同一逻辑出现第三次再抽象」——模板之间的重复是**可接受且期望的**，不要搞模板继承机制。

## 验收标准

```bash
cd sdk-python && pytest tests/templates -q     # 遍历五个模板：生成 → 起服务 → 跑冒烟用例
```

- [ ] 五个模板各自 `keel new` 生成后能直接 `keel dev` 起来
- [ ] 每个模板 ≥ 10 条冒烟用例且全绿
- [ ] 每个模板的 `agent.yaml` 通过 `manifest.schema.json`
- [ ] `tool-agent` 的审批路径走通：`suspend` → 本地批准 → `resume` → 执行完成
- [ ] `graph-agent` 杀进程后能从 checkpoint 恢复
- [ ] `supervisor` 的委派串在同一个 trace 里，预算向下传递
- [ ] `java-spring` 一个进程两个 agent 各自挂载成功
- [ ] 五个模板全部通过 `.cursor/hooks/forbidden-patterns.py` 的扫描
- [ ] 源码扫描：没有任何形如 API Key 的字符串，没有 import 厂商 SDK
- [ ] 每个模板的 `.gitignore` 含 `.keel/`

## 明确不做

- 不做 Dify 模板（P3-8）
- 不做正式 CI 模板（P2-3），这一期只放 `pytest` 占位
- 不做模板的版本管理和远程更新
- 不做 `keel/python-runtime` 基础镜像本身（待确认后单独排）
