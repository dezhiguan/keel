# 生命周期验收智能体

独立镜像运行 `app.py`。注册清单是 `agent.yaml`，Kubernetes 部署清单在
`deploy/k3s/services/qa-lifecycle-1016.yaml`。流水线发布镜像后，需用同一份
`agent.yaml` 在控制台注册或更新智能体，确保共享工具 `shift.policy` 获得授权、
知识库凭据写入 `keel-qa-lifecycle-1016` Secret。

评测集 `qa-lifecycle-1016/smoke` 的补充用例在 `evals/seed.jsonl`。在部署容器中运行
`python evals/seed_dataset.py`，脚本按问题去重，可以重复执行。随后从控制台触发
评测，并检查工具调用、知识库引用、提示词版本和链路追踪。
