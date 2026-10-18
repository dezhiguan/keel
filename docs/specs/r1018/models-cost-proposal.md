# 模型与成本页对齐 2026-10-18 优化稿

## 目标

`#/models` 的版式、文案和可点击操作与 `docs/console/Keel-优化效果-20261018.html` 的「模型与成本」一致：三个 tab（模型与渠道、Key 与配额、路由），渠道份额、熔断演示、Key 覆盖、路由发布回滚和回放试算都在页面内完成。

这一页标「提案」。数字和规则按优化稿演示，不改薄网关，也不替换已有账单、配额、告警接口。

## 改哪些文件

```
docs/specs/r1018/models-cost-proposal.md
console/src/views/models/modelCost.ts
console/src/views/models/modelCost.test.ts
console/src/views/models/ModelGateway.vue
```

## 界面对齐

- 原型位置：`docs/console/Keel-优化效果-20261018.html` 的 `#v-model`。
- 渠道表：厂商上限、已分配、当前用量、超分配状态、按渠道熔断。熔断演示 10 秒，之后半开，探测后恢复。
- Key：点行编辑同环境渠道份额；份额超出厂商上限不能保存；路由可能选到、但 Key 没允许的模型要标出来。
- 路由：权重滑杆、Key 覆盖、不覆盖不能发布、一键补授权、回滚、回放试算。
- 新增渠道、切换 prod 千问渠道、模拟一次请求，结果和优化稿同一套分支。

## 验收标准

```bash
cd console && npx vitest related src/views/models/modelCost.ts src/views/models/ModelGateway.vue --run
```

- [ ] 三个 tab、表头、提案说明、按钮文案与优化稿一致。
- [ ] 初始演示数据下，生产千问已分配 77%，DeepSeek 显示超分配。
- [ ] 有 Key 未覆盖时发布被拒绝；补得上的授权是 RPM 50。
- [ ] 只有一版时回滚提示不能回滚。

## 明确不做

- 不把渠道、逻辑模型和回放接到 keel-llm。
- 不改 `contracts/`，不加后端接口。
- 不改左侧菜单和其他页面。
