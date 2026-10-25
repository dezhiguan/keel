# 跨会话原文记忆验收 · 2026-10-25

已部署并使用真实登录用户 guandezhi（54）完成跨会话验收。应用 0.3.8 / 官方锁定SDK 1.0.17；平台与控制台镜像源码 05e303ec5b96，应用业务源码 7e768488a12ca231774dbdecbd11b7510702a7af。平台与应用分别合并到main：0429ff1、07dba08。三个目标Deployment均2/2 Ready。

## 行为与边界

keel-assistant/staging 的原文保留至用户删除；迁移仅延长尚未过期原文，不恢复此前已清理数据。原来的本会话摘要/事实能力继续独立运作。跨会话工具按可信登录用户、agent、env过滤，只查其他会话；原话默认只查user消息，不把当前提问或assistant回复冒充用户说过。普通新会话也可由模型按需调用历史工具。

支持原话与中文二元词/英文关键词检索，最多6条、每条1200字符；这不是任意语义检索或无限上下文。新会话不继承旧执行授权；历史内容仅作不可信参考。

## 真实验收

源会话 `ss_1787e93f` 只收到项目事实并回复“收到”；新会话 `ss_e1409f5d` 未提供答案，实际检索后答出 HYACINTH-8427 / 12月17日并链接源会话。写入与首次查询分别落在 mtkn5 / j79dp 两个Pod，排除进程缓存冒充跨会话记忆。

| 场景 | 结果 | Trace |
|---|---|---|
| 新会话按项目关键词回查代号、日期 | 正确，含来源链接 | 6090969e65ec246d854f07f76094e830 |
| 原话“你好啊” | 正确命中10月25日与10月20日用户原文；exact检索 | 4e48671a59013a4239cb5b7aa105d43e |
| 从未发送的随机原话 | 实际命中0条，未宣称从未说过 | 3296947e37bd5c8a509c34625a2b3ae6 |
| 删除独立测试源会话后回查其独特短语 | 删除HTTP200，实际命中0条 | 390aa5f214d35767d93326faecc4d374 |
| 第三个新会话，不提示“以前”，直接问项目检查日期 | 模型主动调用历史工具，正确答12月17日，区分用户原文与旧assistant回答 | 69401b235e21ece1da8ec59f16d31f9b |
| 页面来源链接 | 点击10月20日链接打开首10项之外的旧会话，API与DOM均验证原始user“你好啊”；页面显示“原文 保留至删除 · 支持跨会话回查” | 浏览器验收 |

真实SSE final元数据均为application_version=0.3.8、prompt answer v13、prompt_fallback=false；read_evidence包含真实keel.history.search消息id与matchMode。没有通过固定回答/假网关替代模型验收。保留源、查询与第三会话供复核，删除的仅为专用删除测试会话 ss_870ec146。

## 验证与部署

应用本地265通过、18项既有DB依赖跳过；控制台215通过及生产构建；真实PG专项19通过，覆盖跨会话、90天旧列表、infinity持久化、删除、过期、Unicode及owner/env/agent隔离。平台完整Maven测试、SDK/Java既有CI和候选镜像构建通过。第二个真实用户未参与线上测试，不能把PG隔离测试称为双真实用户验收。

平台构建：https://github.com/dezhiguan/keel/actions/runs/40156456231 。应用CI：https://github.com/dezhiguan/keel-assistant/actions/runs/40156989630 。Actions制品额度已满；应用CI明确保留上传警告，测试和构建仍为必需步骤。

部署只更新server、console和个人助理。server/console使用候选ACR镜像；助理完整Docker构建通过后，因传输缓慢，使用已核验的旧不可变OCI运行环境构建独立应用镜像（相当于FROM旧运行环境后COPY固定git archive，不编辑运行中容器，不更改SDK）。最终镜像 `docker.io/library/keel-assistant:history-7e768488a12c`，manifest `sha256:7523235e97a992d5acda8f359706e7c6d2e5e5dc923a35d1a301156a8c67b2e2`。构建脚本、base digest及两个Pod各51个应用文件SHA256零差异证据归档。镜像在当前单节点containerd中，新增节点须分发同一镜像。

通过真实控制台工具授权API授权keel.history.search 1.0.0，再以expectedHash CAS更新候选清单；未改模型预算、知识库授权或既有工具权限。合并提交与证据文档使用[skip ci]，避免共享CD根据历史部署债务滚动其他员工；实际候选CI与定向部署已完成。既有100条正式业务门禁结论保持原样，本专项通过不冒充整体MVP正式发布通过。

完整应用侧证据：https://github.com/dezhiguan/keel-assistant/blob/main/docs/acceptance-cross-session-history.md 。登录密码、Cookie、用户票据均未归档。
