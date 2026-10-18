-- chat-demo 已有独立 Service，登记地址仍指向 echo-agent 时调试会被拒绝，页面只能看到空的失败。
UPDATE agent_version
SET manifest_json = jsonb_set(
        manifest_json,
        '{spec,runtime,endpoint}',
        '"http://chat-demo.keel-system.svc.cluster.local:8000"'::jsonb)
WHERE agent_name = 'chat-demo'
  AND manifest_json #>> '{spec,runtime,endpoint}' LIKE '%//echo-agent.%';
