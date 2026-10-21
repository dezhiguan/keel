# 个人助理门禁缺陷的SDK/平台适配

本任务修复87/100仍未通过的真实原因，不降低0.85/2pt或移除critical用例。业务spec在独立keel-assistant的docs/spec-gate-remediation.md。

SDK1.0.2输入护栏在入口前检查整个input JSON，正常中文引述被误拦，伪造system角色未识别。补充明确分析且不执行的用户引述处理，检查引述外指令、拒绝伪造角色、独立测试注入包装逃逸；外部工具/KB指令仍不可信。不得将enforce改shadow/off。按既有sdk-v1.*正式发布补丁1.0.3，不覆盖1.0.2。

契约先增ErrorEvent可选meta，再更新生成的Java/Python模型及SDK KeelError/ASGI错误事件。meta仅使用业务已脱敏的工具尝试、提示词版本，不记录票据/Secret。保留标准code/message/trace_id/run_id/retryable，不在error后输出final；旧消费者可忽略可选字段。

评测上下文可提供服务器核实的当前环境/只读授权配置，供拒绝/环境边界的语义核验使用，绝不采信模型自报权限。第二真实用户入口按实际认证/现有准入处理，当前只有单用户准入，未收到第二真实账号前保持BLOCKED，不签发伪造用户、不使用管理员Cookie作为运行凭证。平台改动、SDK发布和业务变更分开提交验收。

正式1.0.3发布后补充组合反绕过审查，发现“先声明分析/不执行，随后明确要求执行引文”的矛盾请求还必须取消引述豁免。补充该阴阳性测试与检测，使用正式1.0.4发布，不覆盖1.0.3 tag或制品，个人助理只消费最终通过反绕过验证的版本。

## SDK1.0.5：收尾schema与禁止选择同时传递

0.3.2真实运行ev_760e7a606e174169b073a321de89ebae的knowledge-pagination在预检索成功后仍返回TOOL_NOT_GRANTED（Trace01735d92fdaea1556c3393f0f9077350）。核实1.0.4传tool_choice=none但不带tools，和r1019/bounded-tool-refinement.md“保留工具schema”的要求不一致。Qwen官方文档2026-10-22核实示例同时传tools与tool_choice=none：https://help.aliyun.com/zh/model-studio/qwen-function-calling 。

仅在工具轮次耗尽的收尾请求保留原schema并发送none，SDK运行时active_tools/allowed_names仍为空，不执行任何返回tool_call，即使供应商违反none也返回标准TOOL_NOT_GRANTED而非final。普通空工具聊天、默认无限制轮次、审批恢复、manifest授权保持原边界。更新真实SDK wire测试断言schema与none同时存在，保留恶意返回不得执行测试；流式/命名工具映射继续验证。完整SDK测试通过后正式sdk-v1.0.5发布，业务仅从正式wheel锁版本及哈希，不复制源码。无契约/控制台API字段变化。

本机全量SDK首次为277通过、2个12进程/双进程spawn测试在20秒启动等待超时；当时负载50.95/72.80，测试结束后独立挂起记录12/12均在，非丢失记录证据。保留原失败日志，不改等待阈值、不跳过并发用例。SDK CI补充独立Linux完整测试job，测试全部runtime/guard/loop（既有CI只测生成契约），在该job通过后才发布1.0.5。

Linux完整SDK CI首轮277通过，两个模板测试因job未装graph模板锁定依赖、默认JDK不支持release21失败；两个并发测试已通过。补齐JDK21与既有templates/graph-agent/requirements.lock，不修改模板测试或跳过Java/graph项目。SDK尚未打1.0.5发布tag，等完整CI实际通过。
