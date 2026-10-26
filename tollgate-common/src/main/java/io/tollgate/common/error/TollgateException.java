package io.tollgate.common.error;

import lombok.Getter;

/**
 * 网关业务异常。携带错误码与可选的上游原始错误信息，便于日志追溯。
 *
 * <p>不要在数据面热路径上用它做流程控制——它是异常，不是信号。
 */
@Getter
public class TollgateException extends RuntimeException {

    private final ErrorCode errorCode;
    /** 触发的车道标识，仅在调度类错误中有值 */
    private final String laneCode;
    /** 是否可重试：超时、5xx、429 为 true，请求参数错误为 false */
    private final boolean retryable;

    public TollgateException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null, null, false);
    }

    public TollgateException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null, false);
    }

    public TollgateException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause, false);
    }

    public TollgateException(ErrorCode errorCode, String message, String laneCode,
                             Throwable cause, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.laneCode = laneCode;
        this.retryable = retryable;
    }

    public static TollgateException retryable(ErrorCode errorCode, String laneCode, Throwable cause) {
        return new TollgateException(errorCode, errorCode.getMessage(), laneCode, cause, true);
    }
}
