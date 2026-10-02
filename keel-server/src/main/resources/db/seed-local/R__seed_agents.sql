-- 本机演示数据，只在 local profile 加载。名单取自控制台原型。
-- 用可重复迁移，排在所有版本化迁移之后执行，以后加表不会和版本号冲突。

INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness) VALUES
    ('careermate',    '职业助手',     'AGENT', 'code', 'Java',   '人力数字化组', 'lin',   'ONLINE',     'k8s'),
    ('askdb',         '问数',         'AGENT', 'code', 'Python', '数据平台组',   'zhou',  'ONLINE',     'k8s'),
    ('offshore-wind', '风机维检',     'AGENT', 'code', 'Python', '新能源事业部', 'chen',  'DEGRADED',   'k8s'),
    ('cs-bot',        '客服机器人',   'AGENT', 'dify', NULL,     '客户服务部',   'wang',  'ONLINE',     'probe'),
    ('ops-copilot',   '运维副驾',     'AGENT', 'code', 'Python', '研发效能组',   'amy',   'REGISTERED', 'k8s'),
    ('prd-agent',     'PRD 助手',     'AGENT', 'code', 'Java',   '研发效能组',   'amy',   'ONLINE',     'k8s'),
    ('code-review',   '代码评审',     'AGENT', 'code', 'Python', '研发效能组',   'liu',   'ONLINE',     'k8s'),
    ('test-gen',      '测试生成',     'AGENT', 'code', 'Java',   '研发效能组',   'amy',   'ONLINE',     'k8s'),
    ('ci-doctor',     'CI 医生',      'AGENT', 'code', 'Python', '研发效能组',   'liu',   'OFFLINE',    'heartbeat'),
    ('dev-copilot',   '研发副驾',     'AGENT', 'code', 'Java',   '研发效能组',   'amy',   'DRAFT',      'k8s'),
    ('rag-forge',     '知识检索',     'SERVICE', 'code', 'Java', '数据平台组',   'zhou',  'ONLINE',     'k8s')
ON CONFLICT (name) DO NOTHING;
