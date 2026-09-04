package com.chh.autosense.routing;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 常识/型号问题直答(FR-019 路由 1/3):不触碰任何设备。
 * 流式输出(FR-021):token 经回调逐段产出。
 */
public interface DirectAnswerer {

    /**
     * @param question  用户问题
     * @param sessionId 对话记忆 memoryId(R15)
     * @param onToken   逐 token 回调
     * @return 在流式响应完成后携带完整回答文本的异步结果
     */
    CompletionStage<String> answer(String question, long sessionId, Consumer<String> onToken);
}
