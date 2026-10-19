# R1019 评测控制路径不依赖外部链路展示

memory第二轮门禁核验连续两次POST /eval/qa-memory-1019/runs在约60020ms返回504，active读取200但无任务。证据eval-start-transient-504.json/png。调用链：EvalJobs.start/reconcile调用registry.detail，ElementEvidence为详情页同步查询列表并逐个读取至多8条外部trace；观测服务慢/限流时阻塞控制操作。

增加仅从PG读取的registeredDetail（登记、版本、实例和资源状态），detail在此基础上才做用户页面的记忆/链路/自检证据补充。EvalJobs启动及调度使用registeredDetail，保留全部原有资源ACTIVE、状态、语言、环境和就绪实例校验。禁止靠取消资源校验或延长Nginx超时绕过问题。验证外部证据读取抛错时登记信息仍可读，页面完整详情仍请求证据；部署后重试同一页面完整51条并验证未发布评分不替换基线。
