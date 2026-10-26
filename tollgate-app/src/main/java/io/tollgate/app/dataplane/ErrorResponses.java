package io.tollgate.app.dataplane;

import io.tollgate.common.error.ErrorCode;

import java.util.Map;

/**
 * OpenAI 风格错误响应体。客户端拿到的错误结构与 OpenAI 官方一致，
 * 现有 SDK 无需任何改造即可识别 4xx/5xx。
 */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static Map<String, Object> of(ErrorCode code, String message) {
        return Map.of("error", Map.of(
                "message", message,
                "type", "tollgate_error",
                "param", "",
                "code", code.getCode()
        ));
    }
}
