# 个人助理只读工具与用户上下文

先核实：现有 ToolInvokeController 仅有服务私钥身份，调用白名单未包含员工；控制台API本身不能作为服务入口。SDK1.0.2 remote工具调用支持任意JSON参数，无需复制或升级SDK。

新增共享低风险只读工具 keel.employees.list / keel.employees.detail，受现有工具注册、console授权、候选manifest read/low声明三重约束。另加目标资源授权记录（控制台写入，SERVICE禁止），每agent限定dev/staging、目标组织/ID范围；无记录拒绝。禁止泛化代理任意API。

Console认证链路为每次聊天生成短期RS256只读上下文（独立aud/issuer，五分钟，agent、runtime环境、session、user_id、username），使用现有平台签名密钥。透传在input._keel_read_context中，既有SDK input对象可承载；不放模型消息、日志或审计payload。共享工具调用带_authorization参数，由业务封装固定取本次input，不加入模型参数Schema。验证签名、期限、绑定agent和runtime环境、当前console用户仍获准进入、权限记录有效；不相信text/user_id或服务主体自称的用户。

用户权限按平台现有console账户权限收敛：当前只允许通过ConsoleUsers验证的ADMIN读取其获授权组织/ID，其他角色拒绝，后续支持更多角色另行spec。目标env为工具必填枚举dev/staging（prod不开放），列表分页有count/total/complete，详情严格ID/环境。读取注册服务/仓储只读业务投影，不调用self-check、health探针或写端点；白名单仅ID、名称、职责（缺省未知）、环境、版本、登记状态、health unknown、查询时间、数据源。审计记录实际认证user作为actor，仅env/target/count白名单，不记录上下文票据。

知识范围通过现有知识授权/资源流程，不绕过候选清单直接授权。知识库及工具授权变更后门禁重跑。平台改动独立提交/测试/部署；不修改共享路由、其他密钥、预算。

验证：签名伪造/过期/跨agent/env、无身份、无工具grant、无资源grant、错误Schema/未知ID拒绝；白名单/分页/组织隔离；聊天payload不泄露票据到trace。控制台现有入口兼容，OpenAPI更新与页面对齐清单同步。评测不能伪造用户；正式员工评测需平台签发评测上下文绑定触发者，或独立真实console验收并准确标注覆盖限制。

知识检索另经低风险共享工具keel.knowledge.authorize核验同一用户短期上下文及当前环境组织授权，返回授权成功不返回凭证；检索仍由ctx.knowledge.search使用仅授权KB的独立只读Key。无用户上下文的直接invoke/评测不能借助智能体Key越权检索。业务本地回调通过ContextVar绑定当次ctx，不存会话或权限缓存；模型参数Schema永远没有授权票据。

核实CodeCandidateService禁止通过普通候选编辑改变知识/工具边界，LifecycleService也拒绝重复注册已有ACTIVE资源。因此新增显式管理员POST read-capabilities：只适用原纯聊天基线，固定六个只读工具（包含本地封装）、最多一个目录知识库，先验证已有共享工具批准。复用RagForgeAccess既有KB_LIST/READ发放，只更新本智能体Secret知识字段，保留LLM及私钥；不通过候选manifest绕过授权。原候选接口限制保持。scope与capabilities均须独立明确console操作后才生效。

SDK1.0.2平台单轮worker仅传input.text，不携带可信用户授权；不得用服务身份冒充触发者。保留SDK版本，正式聊天门禁继续平台worker执行；新只读能力通过认证控制台集成评测逐条签发用户上下文。控制台X-Keel-Eval-Run透传到agent SDK，PG和SDK都不写普通会话。新能力报告独立运行号/数据集/工具实参/返回/来源支持，不能与聊天均分混算。

双实例故障验收还要求流中断可辨认。核实DebugSseRelay在无final/suspend/error时收到EOF原先静默返回null；修复为IOException，由既有控制台适配输出规范error。已有SDK error帧原样透传，不重复伪造final。单元覆盖token后断流以及已有error/suspend终止。

知识授权待确认期间允许分阶段推进：read-capabilities显式knowledgeBases=[]只批准固定只读工具，不创建KB凭证；后续仅对相同固定read/low工具集合、原无知识库的候选增加单一已授权库。不能替换已有知识权限或夹带其他工具。缺少KB时知识调用明确拒绝，不发布完整0.2。

授权写入按高风险同步审计处理。组织/环境授权和审计同一事务；能力审批在外部KB凭证发放之前成功写入审计，失败拒绝。候选镜像复用现有ACR的构建/推送及集群拉取方式，生成独立提交标签，不修改注册表凭证或共享路由；保留前一镜像作平台回退。
