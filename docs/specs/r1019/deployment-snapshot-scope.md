# R1019 被取消部署的变更不能丢失

2d22deb 的 CD 39244469276 被后续侧栏提交123cefb取消；123cefb 的39244612154仅按event.before比较，只部署前端却成功，待部署的后端与SDK修复丢失。已快进保留侧栏修复。

发布范围改为对照最后一次完整部署快照。只有本次所有有变更的后端、前端、SDK运行服务都成功且冒烟通过后，才上传名为keel-deployed-snapshot的仅含提交SHA的artifact。main push读取该artifact对应workflow_run.head_sha作为diff起点；没有标记时保守全量部署。PR继续对照目标分支，手动触发仍全量。取消/失败不能写新快照，下次推送累计补齐所有未部署变更。前端单独成功不能成为未完成后端部署的新基线。

GitHub官方Artifacts REST接口支持name过滤、workflow_run.head_sha和Actions只读权限，已核对文档及当前仓库返回。读取全部标记分页后按created_at选最新；只接受main、同一仓库、未过期标记。上传使用actions/upload-artifact的运行时凭据，不增加仓库写权限，不含任何密钥。

来源：https://docs.github.com/en/rest/actions/artifacts 、https://github.com/actions/upload-artifact 。

验证：临时Git仓库先改后端再改前端，event.before只含前端，但部署快照范围必须同时包含后端；首次无快照包含全部服务；工作流YAML及依赖图校验，正常完整CD后生成标记。
