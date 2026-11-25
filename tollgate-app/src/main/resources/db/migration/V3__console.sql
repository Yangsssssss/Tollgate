-- =====================================================================
-- Tollgate V3：产品化控制台
--   * console_user：注册用户，一人一租户（注册即建租户）
--   * tenant.balance_micros：预付费余额，单位百万分之一元（micros）
--   * tariff：费率表，按模型定价，计量落账时快照计价
--   * balance_ledger：余额流水，(tenant_id, ref) 唯一键实现扣费幂等
-- 金额约定延续 V1：全整数 micros，杜绝浮点误差
-- =====================================================================

-- ---------------------------------------------------------------------
-- 租户加余额列。预付费模型下内存是运行时权威，DB 是持久化副本：
-- 扣费流水由 BillingEngine 批量同步到本列（balance_micros + delta 增量式更新，
-- 与充值通道的并发写天然可交换），重启后从本列恢复内存缓存
-- ---------------------------------------------------------------------
ALTER TABLE tenant
    ADD COLUMN balance_micros BIGINT NOT NULL DEFAULT 0 COMMENT '预付费余额，micros（百万分之一元）';

-- ---------------------------------------------------------------------
-- 控制台用户。一人一租户：注册时同事务建 tenant + console_user，
-- api_key 复用 V1 表（tenant_id 关联），密钥安全语义不变
-- ---------------------------------------------------------------------
CREATE TABLE console_user (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT        NOT NULL,
    username      VARCHAR(64)   NOT NULL,
    password_hash VARCHAR(100)  NOT NULL,
    email         VARCHAR(128)  NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_console_user_username (username),
    UNIQUE KEY uk_console_user_tenant (tenant_id),
    CONSTRAINT fk_console_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_console_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '控制台用户。一人一租户，密码只存 BCrypt 哈希';

-- ---------------------------------------------------------------------
-- 费率表：单价按每 1K token 计，micros 计价
-- 计费时按当时费率快照算 cost，调价不回溯历史账单（与 toll_record 注释一致）
-- ---------------------------------------------------------------------
CREATE TABLE tariff (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    model_alias              VARCHAR(128)  NOT NULL,
    prompt_micros_per_1k     BIGINT        NOT NULL,
    completion_micros_per_1k BIGINT        NOT NULL,
    status                   VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    created_at               DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tariff_alias (model_alias),
    CONSTRAINT chk_tariff_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '费率表。cost = ceil(prompt/1000*p_in + completion/1000*p_out)';

-- ---------------------------------------------------------------------
-- 余额流水：充值/扣费/赠送的唯一凭据
-- ref 为幂等引用：扣费用 trace_id，充值/赠送用业务单号。
-- balance_after_micros 记录本次变更后余额（并发下是近似值，审计以流水累加为准）
-- ---------------------------------------------------------------------
CREATE TABLE balance_ledger (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT        NOT NULL,
    ref                   VARCHAR(64)   NOT NULL,
    reason                VARCHAR(24)   NOT NULL,
    delta_micros          BIGINT        NOT NULL,
    balance_after_micros  BIGINT        NOT NULL,
    created_at            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_idem (tenant_id, ref),
    KEY idx_ledger_tenant_time (tenant_id, created_at DESC),
    CONSTRAINT chk_ledger_reason CHECK (reason IN ('DEDUCT', 'RECHARGE', 'SIGNUP_GRANT', 'ADJUST'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '余额流水。(tenant_id, ref) 幂等，重放投递不重复记账';

-- ---------------------------------------------------------------------
-- 种子：费率 + 存量租户赠送演示余额
-- ---------------------------------------------------------------------
INSERT INTO tariff (model_alias, prompt_micros_per_1k, completion_micros_per_1k) VALUES
    ('gpt-4o-mini',   1500, 6000),
    ('deepseek-chat',  100,  200)
ON DUPLICATE KEY UPDATE id = id;

-- 存量租户（demo）一次性赠送 ¥20 演示额度
UPDATE tenant SET balance_micros = 20000000 WHERE balance_micros = 0;
