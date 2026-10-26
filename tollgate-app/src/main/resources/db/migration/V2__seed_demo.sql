-- =====================================================================
-- 本地演示种子数据
-- 幂等：全部使用 ON CONFLICT DO NOTHING，重复执行安全
-- 注意：lane 的 base_url 与密钥均为演示占位，接入真实上游前必须替换
-- =====================================================================

-- 厂商
INSERT INTO provider (code, name, protocol) VALUES
    ('openai',    'OpenAI',   'OPENAI_COMPATIBLE'),
    ('anthropic', 'Anthropic','ANTHROPIC'),
    ('deepseek',  'DeepSeek', 'OPENAI_COMPATIBLE')
ON CONFLICT (code) DO NOTHING;

-- 车道：两个 OpenAI 兼容车道 + 一个 Anthropic 车道
-- priority 数值小者优先；同优先级内按 weight 平滑轮询
INSERT INTO lane (code, provider_id, base_url, priority, weight) VALUES
    ('lane-openai',   (SELECT id FROM provider WHERE code = 'openai'),
     'https://api.openai.com',      10, 3),
    ('lane-relay',    (SELECT id FROM provider WHERE code = 'openai'),
     'https://relay.example.com',   10, 1),
    ('lane-deepseek', (SELECT id FROM provider WHERE code = 'deepseek'),
     'https://api.deepseek.com',    20, 1)
ON CONFLICT (code) DO NOTHING;

-- 密钥池：占位密钥，本地演示用
INSERT INTO lane_key (lane_id, secret) VALUES
    ((SELECT id FROM lane WHERE code = 'lane-openai'),   'sk-demo-openai-0000'),
    ((SELECT id FROM lane WHERE code = 'lane-relay'),    'sk-demo-relay-0000'),
    ((SELECT id FROM lane WHERE code = 'lane-deepseek'), 'sk-demo-deepseek-0000')
ON CONFLICT (lane_id, secret) DO NOTHING;

-- 模型
INSERT INTO model (alias, description, context_tokens) VALUES
    ('gpt-4o-mini',  'OpenAI 轻量模型，双车道演示调度', 128000),
    ('deepseek-chat','DeepSeek 对话模型',               64000)
ON CONFLICT (alias) DO NOTHING;

-- 模型 × 车道映射
-- gpt-4o-mini 同时落在 lane-openai 与 lane-relay：这是调度生效的前提
INSERT INTO model_lane (model_id, lane_id, upstream_model, weight_override) VALUES
    ((SELECT id FROM model WHERE alias = 'gpt-4o-mini'),
     (SELECT id FROM lane WHERE code = 'lane-openai'), 'gpt-4o-mini', NULL),
    ((SELECT id FROM model WHERE alias = 'gpt-4o-mini'),
     (SELECT id FROM lane WHERE code = 'lane-relay'),  'gpt-4o-mini', NULL),
    ((SELECT id FROM model WHERE alias = 'deepseek-chat'),
     (SELECT id FROM lane WHERE code = 'lane-deepseek'), 'deepseek-chat', NULL)
ON CONFLICT DO NOTHING;

-- 演示租户
INSERT INTO tenant (code, name, rpm_limit, tpm_limit) VALUES
    ('demo', '本地演示租户', 120, 200000)
ON CONFLICT (code) DO NOTHING;

-- 演示密钥：明文 tg-demo0000000000（仅存 hash，明文不再可查）
INSERT INTO api_key (tenant_id, name, key_prefix, key_hash) VALUES
    ((SELECT id FROM tenant WHERE code = 'demo'),
     '本地演示密钥', 'tg-demo0',
     '2be2e9a5457f85f00356ed6bc68052a875efcadf951b0bf823138ef8239ad5a6')
ON CONFLICT (key_hash) DO NOTHING;
