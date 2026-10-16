# R3-5c 修复交付仓库 CI 工作流 YAML

## 根因

`spec-agent` 候选 PR 的两次 GitHub Actions push 运行均在创建 Job 前失败。`templates/graph-agent/.github/workflows/keel.yml` 的 Gate 步骤把多行 `python -c` 代码写在 YAML block scalar 外；本地 `yaml.safe_load` 同样报 `mapping values are not allowed here`。其它 Python 模板和 SDK `hello-agent` 工作流有同一写法。

## 范围

- 六份模板工作流和 `sdk-python/keel/cli/new.py` 生成的工作流改为合法 YAML；保留单测、Gate、镜像和 staging 注册发布的顺序。
- 模板测试解析每份工作流，并校验关键步骤，防止再次交付 GitHub 无法启动的 CI。
- 元智能体再次交付时使用修正后的模板；现有 PR 必须获得新候选提交并重新经过沙箱与 CI。

## 界面对齐

不改变控制台字段或布局。任务页现有 PR 与门禁状态继续显示实际结果。

## 验收

- 六份模板和 `hello-agent` 工作流均可解析，步骤顺序正确。
- `spec-agent` 新候选提交的 GitHub Actions 产生实际 Job，单测与 Gate 按结果推进。
