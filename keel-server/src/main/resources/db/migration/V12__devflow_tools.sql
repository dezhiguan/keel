-- 研发流程共享工具。员工尚未入库，这里不写授权。

INSERT INTO approval_policy (name, subject_type, approver_type, approver_ref, timeout_minutes, cooldown_hours)
VALUES ('智能体发布审批', 'tool.call', 'ROLE', 'ADMIN', 10080, 0);

INSERT INTO tool (name, scope, owner_agent, owner_org, provider, status) VALUES
('devflow.job.read', 'SHARED', NULL, '平台', 'keel-server', 'ONLINE'),
('devflow.stage.report', 'SHARED', NULL, '平台', 'keel-server', 'ONLINE'),
('devflow.batch.read', 'SHARED', NULL, '平台', 'keel-server', 'ONLINE'),
('devflow.catalog.search', 'SHARED', NULL, '平台', 'keel-server', 'ONLINE'),
('sandbox.run', 'SHARED', NULL, '平台', 'keel-server', 'ONLINE'),
('git.repo.create', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('git.branch.push', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('git.pr.open', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('git.pr.diff', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('git.pr.comment', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('git.pr.merge', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('ci.workflow.dispatch', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('ci.log.fetch', 'SHARED', NULL, '平台', 'git-ci', 'ONLINE'),
('keel.gate.report.read', 'SHARED', NULL, '平台', 'keel-server', 'ONLINE')
ON CONFLICT (name) DO NOTHING;

INSERT INTO tool_version (tool_name, version, schema_json, description, access, risk, approval_policy_id, breaking) VALUES
('devflow.job.read', 'v1', '{}'::jsonb, '读研发任务', 'READ', 'LOW', NULL, false),
('devflow.stage.report', 'v1', '{}'::jsonb, '回写阶段结论', 'WRITE', 'LOW', NULL, false),
('devflow.batch.read', 'v1', '{}'::jsonb, '读批次进度', 'READ', 'LOW', NULL, false),
('devflow.catalog.search', 'v1', '{}'::jsonb, '查工具目录', 'READ', 'LOW', NULL, false),
('sandbox.run', 'v1', '{}'::jsonb, '在沙箱执行一次测试', 'EXEC', 'MID', NULL, false),
('git.repo.create', 'v1', '{}'::jsonb, '在 keel-agents 建私有仓库', 'WRITE', 'MID', NULL, false),
('git.branch.push', 'v1', '{}'::jsonb, '推分支', 'WRITE', 'LOW', NULL, false),
('git.pr.open', 'v1', '{}'::jsonb, '开拉取请求', 'WRITE', 'LOW', NULL, false),
('git.pr.diff', 'v1', '{}'::jsonb, '读拉取请求差异', 'READ', 'LOW', NULL, false),
('git.pr.comment', 'v1', '{}'::jsonb, '评论拉取请求', 'WRITE', 'MID', NULL, false),
('git.pr.merge', 'v1', '{}'::jsonb, '合并拉取请求', 'WRITE', 'HIGH', (SELECT id FROM approval_policy WHERE name = '智能体发布审批' AND subject_type = 'tool.call'), false),
('ci.workflow.dispatch', 'v1', '{}'::jsonb, '触发 workflow', 'EXEC', 'MID', NULL, false),
('ci.log.fetch', 'v1', '{}'::jsonb, '读 workflow 日志', 'READ', 'LOW', NULL, false),
('keel.gate.report.read', 'v1', '{}'::jsonb, '读门禁报告', 'READ', 'LOW', NULL, false)
ON CONFLICT (tool_name, version) DO NOTHING;
