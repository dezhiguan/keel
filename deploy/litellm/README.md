# LiteLLM（P1-2）

两个副本，每个 Pod 1 vCPU + 4Gi（requests = limits），每个 Pod 1 个 worker，必须接 Redis。镜像钉在 `docker.litellm.ai/berriai/litellm:v1.98.0`（LiteLLM 生产文档给出的钉版本写法）。

`config.yaml` 的 `success_callback`、`failure_callback`、`callbacks` 都是空列表，没有 Langfuse。别名必须匹配 `{agent}-{env}`。

单价在 `config.yaml` 的 `model_info` 和 `model-definitions.json`，单位是美元/token。2026-10-03 没有打开厂商价目页复核这四个数。部署前改成当天价格，并在 Langfuse 的 Model Definitions 里配同一组数。缺价格时 LiteLLM 只打一行 WARNING，成本记 0。

`/key/delete`：本机没有正在运行的 LiteLLM，Swagger 没有打开。下线只调用 `/key/block`，不要调用 delete。

厂商 Key 放 Secret `litellm`，不进 git。管理端口不要暴露到公网。

```bash
docker compose -f deploy/litellm/docker-compose.yml config
```
