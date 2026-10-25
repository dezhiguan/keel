# keel/v1 跨会话历史工具

沿用 POST /api/v1/tools/{name}/invoke，name=keel.history.search；X-Keel-Env、服务身份及当前用户只读授权必需。

请求：{query: string(1..500), exact?: boolean=false, role?: "user"|"all"="user", limit?: integer(1..6)=4, _authorization: signed read-context, _session: SDK hashed current session}。不得传owner/userId/目标session等其他参数。

响应R.data：{items: [{id:string, role:string, content:string, offset:integer, totalChars:integer, sha256:string, hasMore:boolean, sessionId:string, title:string, createdAt:ISO8601, url:string}], matchMode:"exact"|"keywords", scope:"previous_sessions", exhaustive:false, queriedAt:ISO8601}。无命中items=[]；鉴权/检索失败走既有错误体，不伪装为空结果。只返回当前用户同agent/env的非当前会话未过期原文。

listDebugSessions响应新增可选retention:string、crossSessionHistory:boolean；现有客户端兼容。

2026-10-25 additive: keyword user hits may include optional followingMessage with the same message fields (id/role/content/offset/totalChars/sha256/hasMore/sessionId/title/createdAt/url). It is the immediate next unexpired message of the same owner/agent/env/session, only when its role is assistant. It is historical assistant evidence, not a claim that the user said its text. Exact search is unchanged. Combined content budget per hit remains <=1200 codepoints (800 hit + 400 context).
