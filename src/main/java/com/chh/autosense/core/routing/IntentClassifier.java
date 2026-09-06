package com.chh.autosense.core.routing;

import com.chh.autosense.domain.enums.Intent;

/**
 * 意图分类器(FR-019/R13):将用户输入分类为四路路由意图。
 * 分类结果只决定"是否进入设备流程";设备写操作仍由状态机+确认门管控。
 */
public interface IntentClassifier {

    /**
     * @param text     用户输入
     * @param sessionId 会话 id(对话记忆 memoryId;终态续聊时可见历史)
     */
    Intent classify(String text, long sessionId);
}
