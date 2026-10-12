# GitHub API 网络转发

应用节点到 GitHub API 不可达，数据机可以访问。`cloud-relay.py` 在数据机
172.25.90.183:8443 监听，仅向 api.github.com:443 转发 TLS 字节，不解密。
git-ci 的 hostAliases 指向数据机，Secret 的 KEEL_GITHUB_API 配为
`https://api.github.com:8443`，保留端到端 TLS 证书校验。

安装到 `/usr/local/sbin/keel-github-relay.py`，使用
`keel-github-relay.service` 启动。服务配置见同目录，安装后执行
`systemctl daemon-reload && systemctl enable --now keel-github-relay.service`。
监听私网地址，不开放公网端口；每个上游 IP 的连接超时为 2 秒。

验收：应用机请求上述 HTTPS 地址返回 200；systemd 服务 active，
重启计数不增长。凭据仅保存在 Kubernetes Secret 中。
