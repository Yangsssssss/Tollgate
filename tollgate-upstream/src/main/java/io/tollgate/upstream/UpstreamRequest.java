package io.tollgate.upstream;

import java.util.List;

/**
 * 与厂商无关的调用请求。适配器负责把它翻译成各家自己的报文格式。
 *
 * <p>刻意保留 {@code rawBody}：有些厂商特有参数（如 Anthropic 的 {@code thinking}）
 * 无法在通用模型里穷举，透传原始 JSON 交给适配器自行取舍，避免每加一个参数就改接口。
 *
 * @param model        上游模型名，由调度层从 {@code model_lane} 解析得到
 * @param messages     消息列表，顺序即对话顺序
 * @param temperature  采样温度，可为 null 表示用上游默认值
 * @param maxTokens    最大生成 token 数
 * @param stream       是否流式返回
 * @param rawBody      客户端原始请求体，供适配器读取厂商特有字段
 */
public record UpstreamRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Integer maxTokens,
        boolean stream,
        String rawBody
) {

    public record Message(Role role, String content) {
    }

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }
}
