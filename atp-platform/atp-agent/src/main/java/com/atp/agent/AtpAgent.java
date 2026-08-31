package com.atp.agent;

import com.atp.agent.intent.IntentCategory;
import io.agentscope.core.ReActAgent;

/**
 * 平台内所有子 agent 的共同面。
 *
 * <p>存在的理由只有一个：{@code ChatService} 要能按路由结果拿到「这一轮该由谁来答」，
 * 而不必为每种意图写一个分支。加一个新 agent 时，
 * {@code ChatService} 一行都不用改。
 */
public interface AtpAgent {

    /** 这个 agent 负责哪一类意图 */
    IntentCategory handles();

    /** 底层 ReActAgent —— 流式对话要用它的事件流 */
    ReActAgent raw();

    default String name() {
        return raw().getName();
    }
}
