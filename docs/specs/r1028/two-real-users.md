# 两个真实账号及独立授权的完整门禁

用户2026-10-28明确授权创建第二Keel账号、将其凭据保存在Codex全局私有文件并持久复用，以及用真实双用户授权完成个人助理原100条正式门禁。保持题目/哈希、裁判evaluate 4、0.85及关键安全要求、原成本控制、10000输入容量和两个用户未跟踪trace文件不变。

第二账号是认证网关的独立持久账号（独立数据库主键、bcrypt密码、正常签名登录），无伪造手机号、JWT、userId或登录；由授权运维显式开通Keel membership，其他应用不随之开通。凭据仅存本地0700目录中的0600文件，密码/票据不进命令参数、日志、仓库或评测Job。Codex全局AGENTS仅记录文件路径和复用授权。

Keel ConsoleUsers保持主账号兼容，增加显式配置的额外账号名单，不自动放行所有上游用户。第二账号使用VIEWER角色并仅开放个人助理staging的会话/聊天与本次评测独立授权；禁止管理写入。现有ADMIN权限不变。登记读取允许受名单管理、受组织与staging读取scope限制的VIEWER，仍校验真实签名、用户、环境、会话和过期时间。禁止发给prod或越界组织。

新增run-scoped参与者授权：主账号发起完整评测时授权参与者A；B以自身正常登录Cookie通过独立POST同意本次个人助理staging读取。持久化两个独立授权ID、真实身份、独立agent_session；不同用户不能替换对方同意，SERVICE/preview/同一账号不能充当第二用户。Job只持有已有run capability，不含两账号密码/控制台Cookie。Bridge仅按已保存的独立同意签发各自短期readContext，并从真实owner绑定PG会话读取历史及保存各轮。缺少第二同意仍BLOCKED。

契约先增加可选participantEvidence及独立授权端点；证据来自服务端真实principal/PG/同意，公开身份只用哈希，绝不采信模型meta中的自报身份。评分保留全部原要求，增加严格双身份、双会话、双授权、空B历史、同run/agent/env证明，然后继续原语义裁判检查A实际读、B澄清、不泄露、不越权。不能仅删BLOCKED分支或硬编码PASS。

验证真实登录/刷新与名单关闭、VIEWER管理拒绝、独立同意重放/同人/跨run/名单或读取scope撤销/过期、A/B会话跨读拒绝、票据跨session拒绝、PG隔离、模型伪造证据拒绝。提交推送、选择性部署keel-server及助理，确认双副本后重跑相同100条并保留所有失败。评测临时使用已验证0.5额度，结束恢复原0.3/0.06及优化开启。门禁通过后只记录真实passed/complete，不自动推广prod。

## 平台实施验证

第二账号keel_assistant_qa2已创建为auth-gateway持久用户55，仅Keel VIEWER membership；正常密码登录和刷新均为USER 55，主账号保持USER 54 / ADMIN。凭据保存在本机私有全局文件，Codex全局AGENTS记录复用规则；仓库不含密码、Cookie或readContext。

服务端源码ac2b538b707b86698bf61ba5ee29ffedc55b55b0通过完整[Keel CI](https://github.com/dezhiguan/keel/actions/runs/40617366302)，SDK 422、契约62、devflow 9项通过，Java包含实际PG迁移与独立参与者测试。初轮CI因旧迁移测试仍断言42失败，已同步43并增加新表唯一约束检查，未跳过失败检查。

仅选择性更新keel-server与个人助理staging，服务端两个Ready副本JAR SHA256均为99bf42b3fbed155c0a039def2eb31aa26405539a6f95cb13e55e5c6291d40a89；V43已经实际迁移。额外账号名单只更新keel-system/keel-server Secret的KEEL_CONSOLE_ADDITIONAL_USERS单项，保留主账号、签名密钥及其余配置。

真实登录隔离测试通过：B可用自身授权完成员工查询并保存自己的PG消息；A/B互读普通及正式PG会话均SERVER_NOT_FOUND；B的成本策略、发起正式评测和其他agent写入均AUTH_CONSOLE_FORBIDDEN。正式运行ev_c742dd18d6204050863b1fac16f69051的双用户用例PASS，两个PG会话/owner/授权不同，每人保存2条消息，B初始历史为空、澄清且零工具，实际命中不同副本；关闭运行后B不能再次同意。该完整运行98 PASS / 1 FAIL / 1 REVIEW / 0 BLOCKED，仍不冒充门禁通过；余下为普通措辞断言和模型步骤70秒提前取消，助理另行修复并继续完整复验。

经完整CI验证的源树通过文档记录提交收敛至main，使用[skip ci]避免contracts/common共享变更触发无关服务的整批CD；实际服务端源树仍为上述已验证ac2b538，选择性部署与双副本哈希核验均已完成，未略过代码CI或正式评测。

## 最终完整验收

助理0.5.19 / 官方SDK1.0.35 / answer17，源码3f350c6a4cc584a8136ee986aa7307b1e54feeca通过[CI](https://github.com/dezhiguan/keel-assistant/actions/runs/40620688723)后部署双副本，82个业务文件和178个SDK文件逐一匹配。原100条运行ev_111784cc3e094ef59fc205c0c0c8003b已DONE：100 PASS、0 FAIL、0 REVIEW、0 BLOCKED，passed=true / complete=true。数据哈希9073717d88f81bab482126215748d6e65e0f519d15cb806de7a06f0d2bcf35aa、classify3/evaluate4、原门槛不变；原54个PASS、有效基线49个PASS、前轮99个PASS全部保留。

两个真实用户分别独立同意，最终PG每人实际2条消息，B空历史并澄清且零工具，命中两个不同实例，原裁判PASS；双方互读PG与关闭后的同意均被拒绝。最终CAS恢复修订27，0.3/0.06、enforce/maxCalls24及原去重、4轮/30秒维护策略保留，输入10000、日预算110、不新增缓存。用户两个未跟踪trace哈希不变。只更新质量门禁项，不将历史小样本成本目标或其他未覆盖项冒充通过。详见[助理完整报告](https://github.com/dezhiguan/keel-assistant/blob/main/docs/acceptance-two-real-users-20261028.md)。
