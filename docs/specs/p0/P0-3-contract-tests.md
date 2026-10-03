# P0-3 契约跨语言一致性用例

## 目标

`contracts/tests/` 下一套与语言无关的 fixture，Java starter 和 Python SDK 各跑一遍，结果一致。改契约时这套用例是唯一的回归保障。

## 依赖

- 前置任务：P0-1、P0-1a（契约冻结）、P0-2（模型已生成）
- 依赖的契约文件：全部六个
- 依赖的外部组件及其真实行为：无（纯本地用例，不起任何服务）

## 改哪些文件

```
contracts/tests/fixtures/**.json
contracts/tests/cases.yaml
contracts/tests/README.md
keel-spring-boot-starter/src/test/java/com/keel/starter/contract/**
sdk-python/tests/contract/**
```

## 接口契约

fixture 目录按契约分，正例反例分开：

```
contracts/tests/fixtures/
├── manifest/{valid,invalid}/*.yaml
├── sse-events/{valid,invalid}/*.json
├── audit-event/{valid,invalid}/*.json
└── canonical/*.json          # 规范化与哈希用例，见实现要点
```

`cases.yaml` 描述每个 fixture 的期望：

```yaml
- file: manifest/invalid/high-risk-without-approval.yaml
  expect: reject
  reason: risk=high 必须带 approval
- file: audit-event/canonical/basic.json
  expect: accept
  canonical_sha256: "3f9e…"   # 两种语言算出来必须等于这个值
```

两边的测试代码只做三件事：读 `cases.yaml`、按 `expect` 断言解析成功或失败、对 `canonical/` 下的用例额外断言 sha256。**不允许任何一边自己另写 fixture。**

## 实现要点

- **这套用例最核心的目标是哈希链能跨语言验证**，不是字段对不对。审计哈希是 `sha256(prev_hash + 规范化 JSON)`，高风险事件由 SDK 同步写、异步事件由 Java 服务消费 MQ 写、校验接口又在 Java 侧——同一条链会被两种语言碰。只要两边的「规范化 JSON」差一个字节，链就断，而且是在生产环境里断，排查成本极高。所以 `canonical/` 这组用例是本任务的重点，不是附属品。

- **规范化规则要写死在 `contracts/tests/README.md` 里，当成契约的一部分**：键按 UTF-8 码点升序、不留空格（分隔符 `,` 和 `:`）、非 ASCII 不转义、时间统一 `...Z`、`null` 字段整个省略而不是输出 `null`、浮点不出现（金额用字符串或整数分）。每一条都要有一个 fixture 专门打它。最后一条尤其要紧：Jackson 和 pydantic 对 `None` 的默认处理不同，不写明必然对不上。

- **反例要能区分「被拒」和「因为别的原因被拒」**。`expect: reject` 只断言失败太粗——schema 写错了导致正例也挂，测试照样是绿的反例。`cases.yaml` 里的 `reason` 要能对应到具体的错误码或 JSON Pointer 路径，两边断言失败位置一致。

- **反例清单起步至少覆盖**（来自各处铁律，不是随便挑的）：
  - manifest：缺 `metadata.name`、`risk: high` 但无 `approval: required`、`delegates` 里是未注册的智能体、`models.byPurpose.embedding.fallback` 非空、枚举值拼错
  - sse-events：`suspend` 缺 `run_id`、`reason` 是未知值、`final` 缺 `run_id`
  - audit-event：`action` 未知值、`payload` 里出现 `captureFields` 之外的字段

- **未知字段必须被接受，不能当成反例**。平台要同时兼容两个小版本，新版智能体发来的多余字段是正常情况。专门放一个 `valid/with-unknown-field.json` 钉住这个行为，防止有人为了"严格"把它改成 reject。

- 不要在这套用例里起 HTTP 服务、连数据库、调外部组件。它要能在 CI 里几秒钟跑完，是改契约时最先跑的那一道。

## 验收标准

```bash
mvn -o -pl :keel-spring-boot-starter test -Dtest='Contract*'
cd sdk-python && pytest tests/contract -q
```

- [ ] 两边跑同一份 `cases.yaml`，用例数相同，结论逐条一致
- [ ] `canonical/` 下每个用例，Java 和 Python 算出的 sha256 相等且等于 `cases.yaml` 里写死的值
- [ ] 故意把 Python 侧的 `None` 处理改成输出 `null`，`canonical` 用例失败（证明这组用例真的在起作用）
- [ ] 每个反例的失败位置两边一致
- [ ] 带未知字段的正例两边都通过
- [ ] 全套用例在 CI 里 10 秒内跑完

## 明确不做

- 不测业务逻辑，只测契约的解析、校验和序列化
- 不测 HTTP 层行为（SSE 分帧、断连）——那在 P0-6 和 P0-11 各自的用例里
- 不做性能测试
