package io.tollgate.common.error;

import lombok.Getter;

/**
 * 网关对外错误码。分类前缀与 HTTP 状态码对齐，方便客户端统一处理。
 *
 * <p>网关对外只暴露 OpenAI 兼容的错误结构，内部错误码仅用于日志与排查。
 */
@Getter
public enum ErrorCode {

    // ---- 认证与额度 401 / 402 ----
    INVALID_API_KEY(401, "invalid_api_key", "通行证无效或已删除"),
    KEY_DISABLED(401, "key_disabled", "通行证已停用"),
    QUOTA_EXHAUSTED(402, "quota_exhausted", "额度不足"),

    // ---- 请求 400 ----
    INVALID_REQUEST(400, "invalid_request", "请求格式错误"),
    MODEL_NOT_FOUND(404, "model_not_found", "模型不存在或无可用车道"),

    // ---- 限流 429 ----
    TENANT_RATE_LIMITED(429, "rate_limit_exceeded", "租户请求频率超限"),
    TENANT_TOKEN_LIMITED(429, "token_limit_exceeded", "租户 token 用量超限"),
    LANE_OVERLOADED(429, "lane_overloaded", "所有可用车道均已过载"),

    // ---- 调度 503 ----
    NO_AVAILABLE_LANE(503, "no_available_lane", "无可用车道"),
    ALL_LANES_FAILED(503, "all_lanes_failed", "所有车道调用失败"),
    UPSTREAM_TIMEOUT(504, "upstream_timeout", "上游响应超时"),

    // ---- 内部 500 ----
    INTERNAL_ERROR(500, "internal_error", "网关内部错误");

    private final int httpStatus;
    private final String code;
    private final String message;

    ErrorCode(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
