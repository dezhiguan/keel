# {任务 ID} {任务名}

> 复制这份模板。P0 放 `docs/specs/p0/`，P1 放 `docs/specs/p1/`，文件名用 `{任务ID}-{短名}.md`，例如 `docs/specs/p1/P1-3-keel-server-schema.md`。
> 写完这份 spec 再动手。Codex 拿到的应该是这个文件的路径，不是一段聊天描述。

## 目标

一句话说清做完之后什么能跑起来。不写背景，不写动机。

## 依赖

- 前置任务：
- 依赖的契约文件：
- 依赖的外部组件及其真实行为（不确定的写明"待确认"，不要假设）：

## 改哪些文件

明确列出允许改动的路径。**范围之外的文件不要动。**

```
keel-server/src/main/java/com/keel/server/registry/**
keel-server/src/main/resources/db/migration/V*__*.sql
```

## 接口契约

贴出本任务涉及的接口签名、表结构、事件格式。以 `contracts/` 为准，这里只是摘录。

## 界面对齐

不能留空。没有界面改动写"无"。

- 原型位置：`docs/console/Keel-控制台前端-20261014.html` 的哪个路由、哪个 tab、哪个区块
- 对齐清单里的行：`docs/specs/r1014/ui-checklist.md` 中本任务编号对应的行
- 页面数据来自哪个 keel-server 接口

## 实现要点

只写容易做错的地方。常规写法不用写，`AGENTS.md` 和 `.cursor/rules/` 里已经有了。

- 
- 

## 验收标准

必须是**可执行的命令**，不是"功能正常"这类描述。

```bash
mvn -o -pl :keel-server test
```

- [ ] 
- [ ] 

## 明确不做

划清边界，防止越做越大。

- 
