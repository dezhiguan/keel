# GitHub API 网络转发

应用节点到 GitHub API 不可达，数据机可以访问。共用
`../langfuse/cloud-relay.py` 在数据机 172.25.90.183:443 监听，
通过 ClientHello 的 SNI 选择两个允许的目标之一，不解密后续 TLS 内容。
git-ci 的 hostAliases 指向数据机，Secret 的 KEEL_GITHUB_API 配为
`https://api.github.com`，保留端到端 TLS 证书校验。

安装方式见 Langfuse README。监听私网地址，不开放新公网端口；
每个上游 IP 的连接超时为 2 秒。8443 在当前私网安全组不可达，不使用。

验收：应用机请求上述 HTTPS 地址返回 200；systemd 服务 active，
重启计数不增长。凭据仅保存在 Kubernetes Secret 中。
