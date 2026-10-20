# 元智能体护栏检测修复

## 核实结果

修复前两个v2实例 meta-agent-74585d6dbc-bcldf / meta-agent-74585d6dbc-rjkbd 的运行清单均只显式声明 inputInjection=enforce；部署后的两个新实例保持相同护栏配置，并已重新实测。

| 检查 | 实际运行值 | 建议 |
| --- | --- | --- |
| 用户输入注入 | enforce，正常clear、攻击blocked | 保持 |
| 工具/知识内容注入 | off，攻击命中但不剔除 | 建议enforce，纳入下一次配置候选评测 |
| 污点后高风险工具 | 缺省approve；目前没有high工具 | 保留审批边界，不当作交付工具已有审批的证明 |
| 系统提示词canary | off | 建议enforce；只检测内部标记泄露，不保证识别所有提示词泄露 |
| PII | 未声明，SDK实际off | 需要脱敏时显式mask，并评测生成产物是否受影响 |

运行端无认证检测返回401；使用合法内部认证后，两实例的user/tool/kb检测均返回200。用户攻击被拦截，工具及知识攻击返回mode=off，保留真实结果，不能称其已启用。

## 修复范围

控制台检测转发未携带X-Keel-Bridge，无法通过元智能体原有认证。服务端使用已有KEEL_META_AGENT_TOKEN，仅向meta-agent转发，不向其他智能体发送，不开放运行端接口。401/403明确提示内部认证配置错误。

护栏页面对未声明PII误用mask缺省，改为与SDK一致的off；显式mask不变。没有修改已发布v2清单、提示词、模型、技能或门禁，不产生新的元智能体版本。

新增受保护运行端认证、跨智能体凭据隔离、认证错误提示及PII缺省回归，本机Java专项9项全部通过，无跳过。

## 最终线上验收

- 修复提交 `92ec5a22f7a0c7f2c04b03f5ea13bede6e77818f` 已推送，CI通过；随后独立的负责人更新提交 `c32b8ec09b2f918e50da9e36ffa08666f36ae996` 包含该修复并替代原CD运行，未覆盖该更新。
- 最新 [CI 39356429146](https://github.com/dezhiguan/keel/actions/runs/39356429146) 和 [CD 39356429125](https://github.com/dezhiguan/keel/actions/runs/39356429125) 均success。服务端已部署认证修复。
- 最终两个运行实例 `meta-agent-789fd6fb54-2fnss`、`meta-agent-789fd6fb54-sdhjm` 均复验：未认证401，合法认证检测正常；真实invoke返回guardrail blocked及GUARD_INJECTION_BLOCKED，无token/tool/final/suspend。
- 控制台同源接口 `/api/v1/agents/meta-agent/guard/check` 的user正常、user攻击、tool攻击、kb攻击均成功返回实际实例结果，分别为clear、blocked、off、off；401故障消除。
- 页面API读回version=v2，模式为input=enforce、indirect=off、tainted=审批、canary=off、pii=off，已纠正PII假启用显示。
- 两个真实攻击trace `3b89d389a705065d271d4a2cd89d3ff8` / `5cb7faef3edb054f188e9aa88e32c922` 均可通过insight读取，generation=0、costCny=0。
- [脱敏结构化证据](evidence/meta-agent-guardrails.json) 保留检测、页面模式、两个实例的SSE摘要与追踪结果。不含认证头或令牌。

本次仅修复平台检测与显示，不修改元智能体护栏策略、提示词、技能或模型行为；上述建议尚未启用。后续配置升级需保留v2快照，以候选和完整回归门禁发布。
