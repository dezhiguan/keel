# R1-5 智能体执行模板

## 目标

`keel new` 默认生成能执行 `ctx.loop` 的项目；图模板用真实 LangGraph 状态图和 SQLite checkpoint；固定流程模板明确声明 `workflow`。

## 依赖

- 前置任务：R1-3。
- 依赖的契约文件：`contracts/manifest.schema.json` 已支持 `spec.kind`；`contracts/invoke.openapi.yaml` 定义调用与挂起协议。
- 依赖的外部组件及其真实行为：无新增外部服务；LangGraph 与 `langgraph-checkpoint-sqlite` 使用模板已有依赖。

## 改哪些文件

```
docs/specs/r1014/R1-5-agent-templates.md
docs/specs/r1014/README.md
docs/specs/r1014/ui-checklist.md
sdk-python/keel/cli/new.py
sdk-python/keel/cli/__init__.py
sdk-python/tests/cli/test_cli.py
sdk-python/tests/cli/test_delivery.py
sdk-python/tests/templates/test_all_templates.py
templates/loop-agent/**
templates/graph-agent/app.py
templates/graph-agent/Dockerfile
templates/graph-agent/tools/checkpoint.py
templates/graph-agent/tests/test_smoke.py
templates/graph-agent/agent.yaml
templates/{chat-rag,tool-agent,supervisor,java-spring}/agent.yaml
```

## 接口契约

- `create_project(name, template="loop-agent")`；CLI `keel new NAME` 同默认值，`--template hello-agent` 仍可显式选择。
- loop-agent 的 manifest 为 `spec.kind: agent`；当前 graph-agent 示例的固定节点流程和其他固定流程模板为 `spec.kind: workflow`。
- graph-agent 对 `ctx.suspend` 只交付 checkpoint ref，图状态由 LangGraph SQLite saver 管理。

## 界面对齐

- 原型位置：`#/agents` 的智能体目录与详情抽屉；模板生成后通过注册进入目录，本任务不改控制台页面。
- 清单对应行：R1-5 在 `ui-checklist.md` 无独立行；登记模板生成的 `kind` 对目录“概览 · 要素检查”后续 R1-8 的数据影响，保持该行未开始。
- 页面数据：后续由 `GET /api/v1/agents/{name}` 返回的 manifest 派生；本任务无新增接口。

## 实现要点

- loop-agent 冒烟测试用假的薄网关 HTTP 响应验证一次工具循环，不要求真实 Key。
- graph-agent 测试要实际执行图并跨进程恢复；不能只搜索源代码关键字。
- 模板复制时保留本地 wheel 安装方式，不访问公网 PyPI。
- graph-agent 镜像安装模板已经声明的 LangGraph 依赖，确保生成项目部署后能导入状态图。
- 既有交付测试明确选择 `hello-agent`，保留其原有协议测试场景；不改变它们的断言。

## 验收标准

```bash
cd sdk-python && PYTHONPATH=. ../.venv/bin/python -m pytest tests/templates/test_all_templates.py -k 'not java' tests/cli/test_cli.py -q
```

- [x] 默认生成 loop-agent，冒烟用例跑通工具循环。
- [x] graph-agent 执行真实 LangGraph 节点并跨进程恢复。
- [x] 固定流程模板生成的 manifest 明确声明 workflow。

## 明确不做

- 不新增外部发布、镜像部署或生产环境调用。
