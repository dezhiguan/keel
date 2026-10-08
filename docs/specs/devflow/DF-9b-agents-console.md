# DF-9b 控制台：智能体页与新建向导

## 目标

智能体列表保留关键字、状态筛选和注册中心数据。分类多一档「元智能体」，卡片带来源，可以切到谱系。新建向导保留代码开发和低代码 Dify，并增加「由智能体生产」。没注册的原型员工用占位卡片，不计入注册中心数量。

## 依赖

- 前置任务：研发任务页已有 `GET /devflow/jobs`。谱系校验和持久化仍归 DF-2。
- 依赖的契约文件：`contracts/console-api.openapi.yaml`。`AgentSummary` 增加可选 `layer`、`devflowJobId`。新增 `POST /devflow/jobs`。
- 外部组件：无。

## 改哪些文件

```
docs/specs/devflow/DF-9b-agents-console.md
docs/specs/devflow/README.md
contracts/console-api.openapi.yaml
console/src/**
keel-server/src/main/java/com/keel/server/devflow/**
keel-server/src/test/java/com/keel/server/devflow/DevflowServiceTest.java
```

## 实现要点

- 列表先拉注册中心，再用原型名册补尚未注册的员工。同名时注册中心的状态、调用、花费盖过占位数字。
- 详情原有概览、manifest、实例、版本保留，多一个「研发记录」。元智能体不提供发起改造。
- 「由智能体生产」提交进内存任务账本。研发类锁定人机协作。元智能体不能作为改造对象。

## 验收标准

```bash
mvn -o -pl :keel-server test -Dtest=DevflowServiceTest
cd console && npx vitest run src/views/agents/employees.test.ts src/views/agents/wizard.test.ts
```

- [ ] 全部 / 业务 / 研发 / 元智能体可筛，谱系可点开
- [ ] 代码开发向导仍能走到预览
- [ ] 由智能体生产提交后进入研发任务详情

## 明确不做

- 把占位员工写进注册中心
- 委托授权、隐藏考题切分（DF-2 / DF-8）
