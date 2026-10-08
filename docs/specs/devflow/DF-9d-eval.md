# DF-9d 控制台：评测中心显示隐藏考题与分差

## 目标

评测中心现有的门禁结论和分维度对比不变，只多两样：由研发流水线生产的智能体，在结论里显示隐藏考题的聚合分和"隐藏 − 可见"的分差（分差超过 0.10 标红）；页面上多一张"待评测确认"卡片，列出等 H2 的研发任务并链到关口处理页。

来源：`docs/console/Keel-全流程智能体化控制台.html` 评测中心增强；`docs/design/Keel-全流程智能体化方案.html` 第 05、06 节（隐藏考题独立计分、只回写聚合分）。

## 依赖

- 前置：现有评测中心（`GET /eval/{agent}/latest`）；DF-9c 的关口处理页 `/approvals/devflow/:jobId`。
- 契约：`contracts/console-api.openapi.yaml` 的 `EvalResult`。
- 外部：隐藏考题服务（DF-8）还没有，真实数据不会带隐藏考题字段，页面显示「—」。本地由 MSW 返回样例。

## 改哪些文件

```
docs/specs/devflow/DF-9d-eval.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/src/api/schema.d.ts
console/src/views/eval/EvalView.vue
console/src/views/eval/evalCopy.ts
console/src/views/eval/evalCopy.test.ts
console/src/mocks/data/eval.ts
console/src/mocks/handlers.ts
```

## 接口契约

`EvalResult` 加可选字段，不升版本：

| 字段 | 类型 | 说明 |
|---|---|---|
| `devflowJobId` | string，可空 | 由研发流水线生产时的任务号；人工编写的智能体省略 |
| `holdout` | object，可空 | `{ score, minScore, cases }`。只有聚合分和条数，没有用例内容 |

## 实现要点

- 分差 = `holdout.score − scoreTotal`，保留两位小数；绝对值大于 0.10 标红并提示"疑似针对可见用例特判"。写成 `evalCopy.ts` 里的纯函数并测试。
- 没有 `holdout` 时，隐藏考题和分差都显示「—」，并注明"人工编写的智能体没有隐藏考题"。
- `devflowJobId` 存在时，在结论旁显示来源任务，链到 `/jobs/{id}`。
- "待评测确认"卡片的数据来自 `GET /runs` 里 `devflowGate=H2` 的挂起运行（DF-9c 新增的字段）；没有时卡片显示"无"。按钮进 `/approvals/devflow/{jobId}`。
- 现有的智能体选择、分维度表、新失败用例、重新跑评测按钮都不改。

## 验收标准

```bash
cd console && npx vue-tsc --noEmit && npx vitest related src/views/eval/evalCopy.test.ts
```

- [ ] 没有 `holdout` 字段的结果，页面与原来一致，只多两处「—」
- [ ] 分差 > 0.10 或 < −0.10 时标红
- [ ] 待评测确认卡片能跳到关口处理页
- [ ] 页面和接口里都没有隐藏考题内容

## 明确不做

- 隐藏考题的切分、存储和 CI 运行（DF-8）
- 改现有评测结论的计算口径
