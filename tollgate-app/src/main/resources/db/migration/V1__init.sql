-- =====================================================================
-- Tollgate V1 数据模型
-- 约定：
--   * 主键统一 BIGINT IDENTITY，不在应用层生成，避免分布式下的段分配复杂度
--   * 状态字段用 TEXT + CHECK 而非 smallint：换取可读性，量级不重要，配置表不差这点
--   * 所有金额/token 用整数，杜绝浮点误差
--   * 时间统一 TIMESTAMPTZ，应用层传 UTC
-- =====================================================================

-- ---------------------------------------------------------------------
-- 租户：隔离与限流的顶层维度
-- ---------------------------------------------------------------------
CREATE TABLE tenant (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    name         TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    rpm_limit    INTEGER     NOT NULL DEFAULT 60,
    tpm_limit    BIGINT      NOT NULL DEFAULT 100000,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE tenant IS '租户。限流（RPM/TPM）与账单的最顶层维度';

-- ---------------------------------------------------------------------
-- api_key：对外下发的虚拟密钥
-- 设计要点：明文只在创建时返回一次，库里只存 SHA-256。
-- key_prefix 用于日志与控制台定位（形如 tg-ab12cd34），不含可逆信息。
-- ---------------------------------------------------------------------
CREATE TABLE api_key (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT      NOT NULL REFERENCES tenant(id),
    name         TEXT        NOT NULL,
    key_prefix   TEXT        NOT NULL,
    key_hash     TEXT        NOT NULL UNIQUE,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE', 'DISABLED')),
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_key_tenant ON api_key (tenant_id);

COMMENT ON TABLE api_key IS '对外虚拟密钥。鉴权时按 hash 查找，明文不落库';

-- ---------------------------------------------------------------------
-- provider：上游厂商。protocol 决定用哪个 UpstreamAdapter
-- ---------------------------------------------------------------------
CREATE TABLE provider (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    name         TEXT        NOT NULL,
    protocol     TEXT        NOT NULL DEFAULT 'OPENAI_COMPATIBLE'
                 CHECK (protocol IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE provider IS '上游厂商定义。protocol 与 UpstreamAdapter#providerCode 对应';

-- ---------------------------------------------------------------------
-- lane：车道。一次调用的实际出口 = 厂商账号 + Base URL + 密钥池
-- priority 数值越小越优先；同优先级内按 weight 平滑加权轮询
-- ---------------------------------------------------------------------
CREATE TABLE lane (
    id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                       TEXT        NOT NULL UNIQUE,
    provider_id                BIGINT      NOT NULL REFERENCES provider(id),
    base_url                   TEXT        NOT NULL,
    priority                   INTEGER     NOT NULL DEFAULT 100,
    weight                     INTEGER     NOT NULL DEFAULT 1
                               CHECK (weight >= 0),
    status                     TEXT        NOT NULL DEFAULT 'ENABLED'
                               CHECK (status IN ('ENABLED', 'DISABLED')),
    timeout_millis             BIGINT      NOT NULL DEFAULT 120000,
    first_token_timeout_millis BIGINT      NOT NULL DEFAULT 30000,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lane_status ON lane (status);

COMMENT ON TABLE lane IS '车道 = 上游渠道。调度内核的选路对象';

-- ---------------------------------------------------------------------
-- lane_key：车道下的上游密钥池
-- 状态语义：ACTIVE 可用；COOLDOWN 因 429/401 临时冷却；DISABLED 人工禁用
-- TODO(V2): secret 走应用层信封加密（KMS 主密钥 + 数据密钥），V1 明文仅限本地演示
-- ---------------------------------------------------------------------
CREATE TABLE lane_key (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lane_id       BIGINT      NOT NULL REFERENCES lane(id),
    secret        TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'COOLDOWN', 'DISABLED')),
    fail_count    INTEGER     NOT NULL DEFAULT 0,
    cooldown_until TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 同一车道下同一把密钥不允许重复登记，同时给种子数据的幂等写入提供冲突目标
    UNIQUE (lane_id, secret)
);

CREATE INDEX idx_lane_key_lane ON lane_key (lane_id, status);

COMMENT ON TABLE lane_key IS '上游密钥池。运行时状态（冷却/失败计数）在内存，DB 只是持久化副本';

-- ---------------------------------------------------------------------
-- model：对外模型别名与元信息
-- ---------------------------------------------------------------------
CREATE TABLE model (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alias           TEXT        NOT NULL UNIQUE,
    description     TEXT,
    context_tokens  INTEGER,
    status          TEXT        NOT NULL DEFAULT 'ENABLED'
                    CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE model IS '对外模型别名。客户端只认识 alias，真实上游模型名在 model_lane 里';

-- ---------------------------------------------------------------------
-- model_lane：模型 × 车道 的可用映射
-- 一条模型可以在多条车道上服务（这正是调度的前提）；
-- weight 为 NULL 表示继承车道自身权重，非 NULL 则覆盖。
-- ---------------------------------------------------------------------
CREATE TABLE model_lane (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_id       BIGINT      NOT NULL REFERENCES model(id),
    lane_id        BIGINT      NOT NULL REFERENCES lane(id),
    upstream_model TEXT        NOT NULL,
    weight_override INTEGER,
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (model_id, lane_id)
);

CREATE INDEX idx_model_lane_model ON model_lane (model_id) WHERE enabled;

COMMENT ON TABLE model_lane IS '模型到车道的映射。调度候选集的直接来源';

-- ---------------------------------------------------------------------
-- request_log：一次调用的生命周期明细，每个 attempt 一行
-- trace_id 标识客户端的一次完整请求（可能经历多次换道重试）
-- ---------------------------------------------------------------------
CREATE TABLE request_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trace_id        TEXT        NOT NULL,
    tenant_id       BIGINT      NOT NULL,
    api_key_id      BIGINT      NOT NULL,
    model_alias     TEXT        NOT NULL,
    lane_code       TEXT,
    attempt         INTEGER     NOT NULL DEFAULT 1,
    status          TEXT        NOT NULL
                    CHECK (status IN ('SUCCESS', 'UPSTREAM_ERROR', 'TIMEOUT',
                                      'RATE_LIMITED', 'NO_LANE', 'CLIENT_ERROR')),
    http_status     INTEGER,
    error_code      TEXT,
    latency_millis  BIGINT      NOT NULL DEFAULT 0,
    streamed        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_request_log_trace ON request_log (trace_id);
CREATE INDEX idx_request_log_tenant_time ON request_log (tenant_id, created_at DESC);
CREATE INDEX idx_request_log_lane_time ON request_log (lane_code, created_at DESC);

COMMENT ON TABLE request_log IS '调用明细。按 (trace_id, attempt) 可完整还原一次请求的换道过程';

-- ---------------------------------------------------------------------
-- toll_record：计量明细，计费的唯一凭据
-- 幂等键 (trace_id, attempt)：重试或落账重放时 INSERT ON CONFLICT DO NOTHING，
-- 保证同一逻辑请求的同一 attempt 只记一次账
-- ---------------------------------------------------------------------
CREATE TABLE toll_record (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trace_id          TEXT        NOT NULL,
    attempt           INTEGER     NOT NULL DEFAULT 1,
    tenant_id         BIGINT      NOT NULL,
    model_alias       TEXT        NOT NULL,
    lane_code         TEXT        NOT NULL,
    prompt_tokens     INTEGER     NOT NULL DEFAULT 0,
    completion_tokens INTEGER     NOT NULL DEFAULT 0,
    estimated         BOOLEAN     NOT NULL DEFAULT FALSE,
    cost_micros       BIGINT      NOT NULL DEFAULT 0,
    latency_millis    BIGINT      NOT NULL DEFAULT 0,
    success           BOOLEAN     NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (trace_id, attempt)
);

CREATE INDEX idx_toll_tenant_time ON toll_record (tenant_id, occurred_at DESC);
CREATE INDEX idx_toll_lane_time   ON toll_record (lane_code, occurred_at DESC);

COMMENT ON TABLE toll_record IS '计量明细。cost_micros 单位为百万分之一元，落账时按当时费率快照计算';
