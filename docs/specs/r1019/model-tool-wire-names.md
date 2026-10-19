# R1019 DeepSeek工具名称兼容

用自身QA Key对同一真实网关做模型协议验证：Qwen接受devflow.catalog.search；DeepSeek在非思考模式仍400，明确function.name不符合^[a-zA-Z0-9_-]+$。证据deepseek-plain-wire.json。平台命名空间工具合法，SDK不能直接把点号名字发给严格校验的厂商。

SDK仅在模型请求边界做确定、长度<=64、无碰撞的外部别名转换；工具schema和历史assistant.tool_calls一起转换，流式/非流式返回恢复平台原名。manifest、授权、实际工具调用和追踪展示继续使用原名。未知/未提供工具仍由既有loop范围检查拒绝。此为模型协议适配，不修改平台工具契约。

验证：带点号名称、合法名称保持、主动构造的别名碰撞、历史回传、流式名字分片、真实DeepSeek受控A/B请求，随后正式环境页面路由/调试/完整评测。

DeepSeek非思考模式受控A/B：同一自身QA Key/提示词/工具schema/参数，改为SDK确定性合法别名后200，48tokens、正常文本、零工具调用；错误与成功原始元信息分别保存deepseek-plain-wire.json、deepseek-aliased-wire.json。

正式环境模型比较使用相同的非思考基线：Qwen既有enable_thinking=false；DeepSeek对同8个自身dev/prod别名设thinking.type=disabled。官方默认思考模式为high，且思考工具调用要求回传reasoning_content，本轮不声称覆盖该模式：https://api-docs.deepseek.com/guides/thinking_mode/ 。更新仅限这8个别名，保留其他智能体与无关参数；沿用安全合并脚本，不输出Secret，随正式环境登记部署阶段应用并滚动网关。

## 网关配置兼容回归

2026-10-19 应用自身QA默认参数时发现 RequestDefaults 的旧白名单不接受 DeepSeek 的 thinking 对象，新Pod启动失败，旧就绪Pod仍服务。先撤回自身8个scope的新增字段，再部署支持后重加。接受的 thinking 必须恰好是 {"type":"enabled"} 或 {"type":"disabled"}，拒绝其他类型、未知type和额外键；继续禁止model/messages/api_key等越权覆盖。覆盖默认值注入、显式请求优先、作用域隔离和非法配置测试。生命周期测试的模拟厂商也应返回请求中的外部别名，并断言最终工具调用/历史恢复原始平台名称。
