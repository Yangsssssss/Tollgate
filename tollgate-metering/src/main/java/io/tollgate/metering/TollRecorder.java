package io.tollgate.metering;

/**
 * 计量记录入口。数据面在请求结束时调用，实现方负责攒批与异步落库。
 *
 * <p>约定：此方法必须是非阻塞的、永不抛异常的。计量失败不能影响用户请求的成败。
 */
public interface TollRecorder {

    void record(TollRecord toll);
}
