# keel/v1 跨语言契约用例

`cases.yaml` 是 Java starter 与 Python SDK 的共同用例索引。`reason` 是预期失败位置的 JSON Pointer（RFC 6901）。用例内的 `registered_agents`、`capture_fields` 是跨契约约束的本地测试上下文，不访问注册中心或数据库；缺省时不施加这两项额外约束。

## 审计规范化与哈希

审计事件的 `hash` 字段不参与自身哈希；其余已知字段构成规范化 JSON，包含 `prev_hash`。计算 `sha256(UTF-8(prev_hash + canonical_json))`；缺失的 `prev_hash` 视为空串。十六进制摘要为小写。

规范化 JSON 固定执行以下规则：

1. 对象键按 UTF-8 字节升序递归排列；数组保留原顺序。
2. 分隔符只用 `,`、`:`，不留空格；字符串遵循 JSON 转义，但非 ASCII 字符原样输出。
3. 时间解析后转 UTC，以 RFC 3339 `Z` 结尾；小数秒去掉末尾的 `0`，全为零时省略小数点。
4. 对象中的 `null` 字段整体省略，包括嵌套对象；未知字段由模型忽略。布尔值与整数保留。
5. 任何浮点值都拒绝，包含嵌套 `payload`。金额须使用字符串或整数分。

`canonical/` 中每条正例都写死 SHA-256。`float-rejected.json` 专门钉住浮点禁令。改动上述规则时，必须同时更新两种语言的实现和同一份摘要。
