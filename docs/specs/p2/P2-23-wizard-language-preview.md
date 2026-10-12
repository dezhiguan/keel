# P2-23 新建向导的语言和实时预览

## 目标

代码开发的新建向导按控制台原型补上语言选择，右侧 `agent.yaml` 从第一步起随表单变化。

## 改哪些文件

```
docs/specs/p2/P2-23-wizard-language-preview.md
console/src/views/agents/wizard.ts
console/src/views/agents/wizard.test.ts
console/src/views/agents/AgentCreateWizard.vue
```

## 实现要点

- 第二步在代码开发下提供 `Python · keel-sdk` 和 `Java · keel-starter`。Java 只留 `java-spring`、`supervisor`；Python 不出现 `java-spring`。
- 注册请求带上所选 `language`。
- 预览标题为「agent.yaml 实时预览」，说明为「右侧随左侧填写变化」。表单变化后向已有的 `POST /agents/manifest-preview` 要 YAML，不再等到第四步。

## 验收

```bash
cd console && ./node_modules/.bin/vitest related src/views/agents/wizard.test.ts --run
```
