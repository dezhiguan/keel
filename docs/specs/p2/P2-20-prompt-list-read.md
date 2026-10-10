# P2-20 提示词列表少打 Langfuse

## 目标

打开控制台「提示词」页时，列表不再为每一条提示词下载全部历史版本正文。一个提示词读失败时，其余行照常显示。两个 keel-server 副本共用同一份标签快照。智能体和环境筛选不再重新请求。代码里带上来、还没生效的版本在 `keel register` / sync 时记到本地，列表不再为了这个标记翻历史正文。

## 依赖

- 前置：P2-16 的列表、sync、门禁和漂移口径继续有效。响应字段不新增、不改名。
- Langfuse：`GET /api/public/v2/prompts` 只有名字、版本号和标签名，没有正文，也没有「标签在哪一版」。`GET /api/public/v2/prompts/{name}?label=` 与 `?version=` 互斥，标签不存在是 404。
- keel-server 没有 Redis。两个副本已经共用 PostgreSQL，快照放这里。

## 改哪些文件

```
docs/specs/p2/P2-20-prompt-list-read.md
keel-server/src/main/resources/db/migration/V14__prompt_list_snapshot.sql
keel-server/src/main/java/com/keel/server/prompt/PromptService.java
keel-server/src/test/java/com/keel/server/prompt/PromptFlowTest.java
keel-server/src/test/java/com/keel/server/prompt/PromptServiceCacheTest.java
keel-server/src/test/java/com/keel/server/SchemaMigrationTest.java
console/src/views/prompts/PromptList.vue
```

## 实现要点

- 列表先打一次项目级提示词列表。每条只读 `dev`、`test`、`staging`、`production` 里实际存在的标签；同一版上已经看到的标签不再重复读。最新版如果不是这些标签之一，再按版本号读一次，用来填最近修改。不读更早的历史正文。详情、保存、sync 查重仍读版本正文。
- 某一条的标签读取失败时，这一行状态为「暂时读不到」，其它行照常返回。项目级列表整体失败时，有上一份快照就用上一份，没有才让这次请求失败。
- 标签快照写入 `prompt_label_snapshot`（单行 JSON，不含正文），60 秒内两个副本都读这一行。刷新用数据库会话锁单飞，避免两个副本同时打满 Langfuse。保存、生效、sync、发布、回滚和门禁回写仍清掉这份快照。
- 门禁和漂移按智能体一次查完 `prompt_promotion`、`release_record`，不再每个提示词各查三次。
- `prompt_code_version` 在 sync 建出版本时写入版本号和 gitSha。列表用它判断「代码里有新版本」：该版本已经出现在某个环境标签上就不显示。环境列一次组装四个；`env` 查询参数仍按原契约裁剪返回字段，但不再因此重打 Langfuse。
- 控制台列表固定请求 `env=all` 且不带智能体。切换顶栏环境和智能体下拉只改本地筛选。

## 验收

```bash
mvn -o -pl :keel-server test -Dtest=PromptFlowTest,PromptServiceCacheTest,SchemaMigrationTest
cd console && npx vue-tsc --noEmit
```

- [ ] 同一提示词有多个版本时，列表请求不按历史版本号逐个下载
- [ ] 连续两次列表、以及另一个 PromptService 实例再读，只打一次项目级列表
- [ ] 一条提示词的 Langfuse 读取返回 500 时，列表仍是 200，该行是「暂时读不到」
- [ ] sync 第二次带上新内容后，列表的 codeVersion 来自 `prompt_code_version`
