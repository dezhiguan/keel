# 路由试算与个人助理默认逻辑模型

2026-10-21，功能提交 `975c35d`、样式补充 `73c9e89` 均已推送main并部署。server/llm为 `975c35df61da`，console为 `73c9e89f261e`，均2/2就绪。助理独立项目0.2.1已发布staging v3，应用源码 `6417a4b`，完整门禁通过。

## 实现与验证

普通路由试算只选Key，从同环境智能体最新登记清单读取默认模型；不再以允许列表里的第一个真实模型替代默认模型。未登记默认模型明确报错并引导高级输入。高级覆盖明确区分逻辑名称与真实名称，保留旧preview接口。

新诊断接口完整枚举已发布分流（含灰度有效权重）、降级、同环境模型/渠道授权、熔断、Key停用和输入长度限制。不随机抽取单一分支，不调用模型或记账；并提示预算、限流和上游连通性不属于本次配置诊断。

界面新增加载/完成时间；切换Key、覆盖模型或路由发布后清空旧结果，过期响应不覆盖新选择。生产截图复查后补上组件独立的双列网格及既有表格类，窄屏改为单列。

- 本地24项后端针对性测试通过：诊断全分支、灰度、缺授权、输入限制、不调用/记账、真实模型别名兼容、同环境清单、默认/覆盖/缺失配置、兼容候选删除fallback与现有权限边界、ArchUnit。
- 196项前端测试、TypeScript及生产构建通过。
- 本地浏览器使用受控响应验证加载、无model默认请求、80/20展示、缺授权、切换清理、过期响应丢弃与高级覆盖；此项为界面测试，不充当真实模型验收。
- 真实认证浏览器完成5次诊断：助理默认chat-default（重复点击两次）、prod逻辑模型完整80/20及DeepSeek降级、qwen-plus真实模型兼容映射、切回默认逻辑模型。无页面异常；最终部署复测同时核对实际grid与表格样式。
- 首次线上脚本固定选择历史careermate-prod别名，因该Key已不存在而超时；改为读取现有prod选项后通过。没有新建或修改Key来迎合测试。

候选接口允许在默认模型不变时删除/清空应用fallback，仍拒绝添加、替换fallback及预算/工具/其他权限变化。助理镜像删除models.fallback，default仍chat-default；保留网关Key的具体模型兼容配置，逻辑模型降级由路由表决定。

## 助理发布

正式评测 `ev_fdbf51cd9aee45839ffd25ef9b8e7962` 全部10条执行完整，9条通过，均分0.9444444444，`complete=true`、`passed=true`，提示词answer v6，镜像匹配，随后通过正式发布接口创建staging v3。保留1条capabilities失败，不修改评分器、数据集或阈值。

发布后聊天、知识检索与staging员工列表3/3通过，3个trace关联6条正token/正费用的已结算网关账单。双实例实际配置均无应用fallback；Secret、Key授权和实际100元预算保持不变。没有发布prod。

完整助理证据在独立仓库 [0.2.1发布报告](https://github.com/dezhiguan/keel-assistant/blob/main/docs/acceptance-v0.2.1-routing.md)。

## 流水线与证据

- [功能CI](https://github.com/dezhiguan/keel/actions/runs/39545677660)、[功能CD](https://github.com/dezhiguan/keel/actions/runs/39545677671)
- [样式CI](https://github.com/dezhiguan/keel/actions/runs/39546679049)、[样式CD](https://github.com/dezhiguan/keel/actions/runs/39546678997)
- [本地测试汇总](evidence/2026-10-21-route-diagnostics/local-tests.json)、[本地界面场景](evidence/2026-10-21-route-diagnostics/local-ui.json)
- [线上接口与界面场景](evidence/2026-10-21-route-diagnostics/live-ui.json)
- [默认逻辑模型](evidence/2026-10-21-route-diagnostics/live-default.png)、[完整分流与降级](evidence/2026-10-21-route-diagnostics/live-branches.png)、[真实模型覆盖](evidence/2026-10-21-route-diagnostics/live-concrete.png)
