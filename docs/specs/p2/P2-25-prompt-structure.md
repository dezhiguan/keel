# P2-25 提示词：第一版和对话结构

## 目标

控制台登记的智能体可以在还没有任何版本时写出第一版。对话型提示词按 system / user / assistant 分行设计，并能改 temperature、max_tokens、top_p。类型在 Langfuse 建出版本后不能改。

## 依赖

- 前置：P2-16。保存、生效、对比的接口不变。
- `POST /agents/{name}/prompts/{prompt}/versions` 已接受 chat 消息数组和 config。config 仍然只认 temperature、max_tokens、top_p。

## 改哪些文件

```
docs/specs/p2/P2-25-prompt-structure.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/prompts/promptView.ts
console/src/views/prompts/promptView.test.ts
console/src/views/prompts/PromptDetail.vue
console/src/styles/console.css
console/src/views/agents/wizard.ts
console/src/views/agents/wizard.test.ts
console/src/views/agents/AgentCreateWizard.vue
keel-server/src/main/java/com/keel/server/registry/service/RegisterManifest.java
keel-server/src/test/java/com/keel/server/registry/service/RegisterManifestTest.java
```

## 实现要点

- 向导增加可选 `promptType`：`text` 或 `chat`。不传时 manifest 仍是 text。控制台默认 chat。Dify 仍然不声明提示词。
- 没有版本时，「写出第一版」打开编辑器。chat 预填一条 system 和一条带 `{{question}}` 的 user；text 预填一段带 `{{question}}` 的正文。
- chat 可以增删消息、改角色。至少留一条，每条都要有内容。角色只允许 system、user、assistant。
- config 三项可留空。temperature 为 0～2，top_p 为 0～1，max_tokens 为正整数。保存时和正文一起写入新版本，不挪任何环境标签。
- 内容和 config 都没变时不新建版本。
- 编辑框高于视口时，正文在框内滚动，保存按钮留在框底。

## 验收

```bash
mvn -o -pl :keel-server test -Dtest=RegisterManifestTest
cd console && npx vitest related src/views/prompts/promptView.test.ts src/views/agents/wizard.test.ts
```

- [ ] 不传 promptType 的登记结果仍是 type text；传 chat 时 manifest 是 chat
- [ ] 没有正文时编辑草稿是对应类型的起步内容，不是空白无响应
- [ ] 非法 config 不会被当成可保存的数字
