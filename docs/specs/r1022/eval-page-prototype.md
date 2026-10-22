# 测评中心收回原型布局

测评中心「分维度对比」上后来叠了「本轮用例结果」和「未通过评分的用例」。原型 `#/eval` 只有门禁结论和分维度表。逐条用例和失败链路改到 Langfuse 实验页查看，页面保留已有的「在 Langfuse 中打开」。

个人助理验收集 `representative-v0.2-100` 的维度标签是英文分类键，数据集说明里的中文分类名备注在标签旁。已是中文或其他智能体的标签不加备注。

## 依赖

- 前置：R4-3 的三个 tab，分维度分页。
- 契约不改。`cases`、`newFailures` 仍由评测结果返回，页面不再渲染。

## 改哪些文件

```
docs/specs/r1022/eval-page-prototype.md
docs/specs/r1014/ui-checklist.md
console/src/views/eval/EvalView.vue
console/src/views/eval/evalCopy.ts
console/src/views/eval/evalCopy.test.ts
```

## 界面对齐

- 原型：`docs/console/Keel-控制台前端-20261014.html` 的 `#/eval`。
- 分维度对比：门禁条、数据集汇总一行、分维度表。去掉两张用例表。
- 维度备注：`chat` 聊天，`context` 上下文，`employees` 员工，`knowledge` 知识，`security` 安全。
- 研发任务门禁、待评测确认两个 tab 不改。

## 验收标准

```bash
cd console && npx vitest related src/views/eval/evalCopy.test.ts src/views/eval/EvalView.vue --run
```

- [x] 页面不再出现「本轮用例结果」「未通过评分的用例」
- [x] 上述五个英文标签带中文备注，中文维度名不加备注
