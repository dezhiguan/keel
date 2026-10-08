# DF-5b 从需求单生成项目骨架

## 目标

人确认需求单之后，用旁边的 `agent.yaml` 和现有模板生成一份可打开的项目。生成只是复制文件，不在 meta-agent 进程里执行生成出来的代码。

## 依赖

- 前置任务：DF-5a。确认后的目录里已经有 `spec.json` 和 `agent.yaml`。
- 依赖的契约文件：`contracts/manifest.schema.json`。本任务不改契约。
- 外部组件：无。不调用模型、GitHub、沙箱。

## 改哪些文件

```
docs/specs/devflow/DF-5b-scaffold.md
docs/specs/devflow/DF-5-meta-agent.md
docs/specs/devflow/README.md
sdk-python/keel/cli/new.py
sdk-python/keel/cli/__init__.py
sdk-python/tests/cli/test_cli.py
agents/meta-agent/app.py
agents/meta-agent/tests/test_meta_agent.py
```

## 接口契约

```text
keel new --from-spec spec.json
```

`spec.json` 必须是对象，且 `title` 非空。同目录必须有 `agent.yaml`。项目名只取 manifest 的 `metadata.name`，命令行再传名字就拒绝。`runtime.language` 为 `java` 时用 `java-spring`，其余用 `tool-agent`。

生成目录与 `keel new` 相同：当前目录下的 `{name}/`。meta-agent 确认后写到 `.keel/meta-agent/{run_id}/{name}/`。模板里的 `agent.yaml` 换成已确认的那份，并再复制一份 `spec.json`。

## 实现要点

- 复用现有模板复制，不新写一套项目布局。
- 复制完成后用 `load_manifest` 再读一遍，读不过就失败。
- 不调用模型，不启动生成出的 `app.py`。

## 验收标准

```bash
/Users/amy/CursorProject/keel/.venv/bin/pytest sdk-python/tests/cli/test_cli.py agents/meta-agent/tests/test_meta_agent.py -q
```

- [ ] `--from-spec` 生成的项目能通过 manifest 校验，名字与已确认的 `agent.yaml` 一致，且含模板里的 `app.py` 和需求单
- [ ] 缺标题、缺旁边的 `agent.yaml`、或命令行另给名字时拒绝
- [ ] meta-agent 回复「确认」后，运行目录下出现该骨架

## 明确不做

- 在沙箱里跑 pytest 或协议自检。沙箱没有只读 deploy key，网络策略也不放行装包，生成的项目送不进去。
- 让模型补业务代码。
- 建仓库、开 PR（DF-5c）。
