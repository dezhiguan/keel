# R3-5d 评审阶段刷新候选交付

## 目标

`DF-0002` 的岗位代码在第 2 轮沙箱通过，但交付仓库的 CI 工作流是旧模板，GitHub Actions 在创建 Job 前失败。任务处于 `WAITING_REVIEW`，原有重试只接受 `FAILED`，无法在保持精确 SHA 的前提下修复旧工作流。

## 范围

- 平台管理员可在 `WAITING_REVIEW` 且沙箱已通过、修正轮次未满 3 次时，请求重新交付同一候选。
- 先向 Git 服务核验 PR 当前 head 仍等于任务记录的 SHA，再读取不可变快照；只复用 `role.py`、`prompts/role.md`、`tests/test_role.py`，并从当前可信模板刷新 `.github/workflows/keel.yml`。
- 新提交使用已有 `expectedSha` 条件推送；清除旧人工/模型评审证据与沙箱通过状态，重新对新 SHA 运行沙箱。不复用旧测试或门禁结论。
- 此路径计入既有最多 3 轮候选限制。仍保持私有仓库分支保护和人工 PR 评审要求。

## 界面对齐

任务详情在 `WAITING_REVIEW` 且轮次未满时显示“刷新候选交付”，复用已有重试 API；刷新后展示新的候选 SHA 和沙箱轮次。更新 `ui-checklist.md`。

## 验收

- PR head 变化、快照缺文件或轮次已满时拒绝刷新。
- 新 SHA 生成后旧评审和沙箱证据不再生效；第 3 轮沙箱通过后进入等待评审。
- 新工作流能由 GitHub Actions 创建 Job。
