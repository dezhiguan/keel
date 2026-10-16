# R3-5g 生成仓库与沙箱使用同一测试入口

## 目标

`DF-0002` 的候选 `b6c2f910…` 在隔离沙箱以 `python -m pytest` 通过 6 项测试；GitHub Actions 以裸 `pytest` 运行时，四项测试在导入仓库根目录的 `role.py` 时失败。

## 范围

- 所有 Python 模板与 `keel new` 生成器在 CI 使用 `python -m pytest -q`，保证仓库根目录在模块搜索路径。
- 不修改生成的岗位代码、测试或沙箱报告；现有候选必须刷新模板、生成新 SHA 并重跑沙箱。

## 界面对齐

没有新界面字段；任务页显示已有的基础设施刷新次数和精确 SHA 验证记录。

## 验收

- 所有 Python 模板工作流包含 `python -m pytest -q`。
- 新候选的沙箱测试和 GitHub Actions 单测均通过；Gate、镜像构建结果另行记录。
