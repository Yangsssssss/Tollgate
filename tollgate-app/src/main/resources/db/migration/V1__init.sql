-- =====================================================================
-- Tollgate V1 数据模型（MySQL 8.0.16+，CHECK 约束在该版本起强制执行）
-- 约定：
--   * 主键 BIGINT AUTO_INCREMENT，不在应用层生成
--   * 时间 DATETIME(3)，应用层统一写 UTC；updated_at 交给 ON UPDATE 自动维护
--   * 金额/token 全整数，杜绝浮点误差
--   * 外键取舍：配置表（低频写）保留 FK 保完整性；
--     日志与计量表（高频写）不加 FK，换写入吞吐——那两类表靠应用层保证引用有效
-- =====================================================================

-- ---------------------------------------------------------------------
-- 租户：隔离与限流的顶层维度
-- ---------------------------------------------------------------------
CREATE TABLE tenant (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    code         VARCHAR(64)   NOT NULL,
    name         VARCHAR(128)  NOT NULL,
    status       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    rpm_limit    INT           NOT NULL DEFAULT 60,
    tpm_limit    BIGINT        NOT NULL DEFAULT 100000,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (code),
    CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '租户。限流（RPM/TPM）与账单的最顶层维度';

-- ---------------------------------------------------------------------
-- api_key：对外下发的虚拟密钥
-- 明文只在创建时返回一次，库内只存 SHA-256（CHAR(64) 存 hex，恰好定长）。
-- key_prefix 形如 tg-ab12cd34，用于日志与控制台定位，不含可逆信息。
-- ---------------------------------------------------------------------
CREATE TABLE api_key (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT        NOT NULL,
    name         VARCHAR(128)  NOT NULL,
    key_prefix   VARCHAR(16)   NOT NULL,
    key_hash     CHAR(64)      NOT NULL,
    status       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    expires_at   DATETIME(3)   NULL,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key_hash (key_hash),
    KEY idx_api_key_tenant (tenant_id),
    CONSTRAINT fk_api_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_api_key_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '对外虚拟密钥。鉴权按 hash 查找，明文不落库';

-- ---------------------------------------------------------------------
-- provider：上游厂商。protocol 决定路由到哪个 UpstreamAdapter 实现
-- ---------------------------------------------------------------------
CREATE TABLE provider (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    code         VARCHAR(64)   NOT NULL,
    name         VARCHAR(128)  NOT NULL,
    protocol     VARCHAR(32)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_code (code),
    CONSTRAINT chk_provider_protocol
        CHECK (protocol IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '上游厂商定义。protocol 与 UpstreamAdapter#providerCode 对应';

-- ---------------------------------------------------------------------
-- lane：车道。一次调用的实际出口 = 厂商账号 + Base URL + 密钥池
-- priority 数值越小越优先；同优先级内按 weight 平滑加权轮询
-- ---------------------------------------------------------------------
CREATE TABLE lane (
    id                         BIGINT        NOT NULL AUTO_INCREMENT,
    code                       VARCHAR(64)   NOT NULL,
    provider_id                BIGINT        NOT NULL,
    base_url                   VARCHAR(512)  NOT NULL,
    priority                   INT           NOT NULL DEFAULT 100,
    weight                     INT           NOT NULL DEFAULT 1,
    status                     VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    timeout_millis             BIGINT        NOT NULL DEFAULT 120000,
    first_token_timeout_millis BIGINT        NOT NULL DEFAULT 30000,
    created_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lane_code (code),
    KEY idx_lane_status (status),
    CONSTRAINT fk_lane_provider FOREIGN KEY (provider_id) REFERENCES provider (id),
    CONSTRAINT chk_lane_weight CHECK (weight >= 0),
    CONSTRAINT chk_lane_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '车道 = 上游渠道。调度内核的选路对象';

-- ---------------------------------------------------------------------
-- lane_key：车道下的上游密钥池
-- 状态语义：ACTIVE 可用；COOLDOWN 因 429/401 临时冷却；DISABLED 人工禁用
-- 运行时状态（冷却/失败计数）以内存为准，DB 只是持久化副本
-- TODO(V2): secret 走应用层信封加密（KMS 主密钥 + 数据密钥），V1 明文仅限本地演示
-- ---------------------------------------------------------------------
CREATE TABLE lane_key (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    lane_id        BIGINT        NOT NULL,
    secret         VARCHAR(512)  NOT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    fail_count     INT           NOT NULL DEFAULT 0,
    cooldown_until DATETIME(3)   NULL,
    last_used_at   DATETIME(3)   NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lane_key (lane_id, secret),
    KEY idx_lane_key_lane (lane_id, status),
    CONSTRAINT fk_lane_key_lane FOREIGN KEY (lane_id) REFERENCES lane (id),
    CONSTRAINT chk_lane_key_status CHECK (status IN ('ACTIVE', 'COOLDOWN', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '上游密钥池。(lane_id, secret) 唯一，同时是种子数据幂等写入的冲突目标';

-- ---------------------------------------------------------------------
-- model：对外模型别名与元信息。客户端只认识 alias，真实上游模型名在 model_lane
-- ---------------------------------------------------------------------
CREATE TABLE model (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    alias           VARCHAR(128)  NOT NULL,
    description     VARCHAR(512)  NULL,
    context_tokens  INT           NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_alias (alias),
    CONSTRAINT chk_model_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '对外模型别名与元信息';

-- ---------------------------------------------------------------------
-- model_lane：模型 × 车道 的可用映射
-- 一条模型落在多条车道上是调度的前提；
-- weight_override 为 NULL 表示继承车道自身权重，非 NULL 则覆盖。
-- MySQL 不支持部分索引（PostgreSQL 的 WHERE enabled 写法），
-- 退化为 (model_id, enabled) 联合索引，查询模式不变。
-- ---------------------------------------------------------------------
CREATE TABLE model_lane (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    model_id        BIGINT        NOT NULL,
    lane_id         BIGINT        NOT NULL,
    upstream_model  VARCHAR(128)  NOT NULL,
    weight_override INT           NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_lane (model_id, lane_id),
    KEY idx_model_lane_model (model_id, enabled),
    CONSTRAINT fk_model_lane_model FOREIGN KEY (model_id) REFERENCES model (id),
    CONSTRAINT fk_model_lane_lane  FOREIGN KEY (lane_id)  REFERENCES lane (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '模型到车道的映射。调度候选集的直接来源';

-- ---------------------------------------------------------------------
-- request_log：一次调用的生命周期明细，每个 attempt 一行
-- trace_id 标识客户端的一次完整请求（可能经历多次换道重试）
-- 高频写入：不加外键、不加 CHECK，靠应用层约束
-- ---------------------------------------------------------------------
CREATE TABLE request_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    trace_id        VARCHAR(64)   NOT NULL,
    tenant_id       BIGINT        NOT NULL,
    api_key_id      BIGINT        NOT NULL,
    model_alias     VARCHAR(128)  NOT NULL,
    lane_code       VARCHAR(64)   NULL,
    attempt         INT           NOT NULL DEFAULT 1,
    status          VARCHAR(24)   NOT NULL,
    http_status     INT           NULL,
    error_code      VARCHAR(64)   NULL,
    latency_millis  BIGINT        NOT NULL DEFAULT 0,
    streamed        BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_request_log_trace (trace_id),
    KEY idx_request_log_tenant_time (tenant_id, created_at DESC),
    KEY idx_request_log_lane_time (lane_code, created_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '调用明细。按 (trace_id, attempt) 可还原一次请求的完整换道过程';

-- ---------------------------------------------------------------------
-- toll_record：计量明细，计费的唯一凭据
-- 幂等键 (trace_id, attempt)：落账走 INSERT ... ON DUPLICATE KEY UPDATE id=id，
-- 保证重试或投递重放时同一逻辑请求的同一 attempt 只记一次账
-- cost_micros 单位为百万分之一元，落账时按当时费率快照计算，价格调整不回溯
-- ---------------------------------------------------------------------
CREATE TABLE toll_record (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    trace_id          VARCHAR(64)   NOT NULL,
    attempt           INT           NOT NULL DEFAULT 1,
    tenant_id         BIGINT        NOT NULL,
    model_alias       VARCHAR(128)  NOT NULL,
    lane_code         VARCHAR(64)   NOT NULL,
    prompt_tokens     INT           NOT NULL DEFAULT 0,
    completion_tokens INT           NOT NULL DEFAULT 0,
    estimated         BOOLEAN       NOT NULL DEFAULT FALSE,
    cost_micros       BIGINT        NOT NULL DEFAULT 0,
    latency_millis    BIGINT        NOT NULL DEFAULT 0,
    success           BOOLEAN       NOT NULL,
    occurred_at       DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_toll_idem (trace_id, attempt),
    KEY idx_toll_tenant_time (tenant_id, occurred_at DESC),
    KEY idx_toll_lane_time (lane_code, occurred_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '计量明细。计费唯一凭据，幂等键 (trace_id, attempt)';
