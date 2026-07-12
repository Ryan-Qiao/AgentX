package com.kama.jchatmind.agent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** 平台预配置的 Web Search MCP 工具入口。用户只能选择是否给 Agent 启用。 */
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class WebSearchTools implements Tool {
    private final SyncMcpToolCallbackProvider callbackProvider;

    public WebSearchTools(SyncMcpToolCallbackProvider callbackProvider) {
        this.callbackProvider = callbackProvider;
    }

    @Override
    public String getName() {
        return "webSearch";
    }

    @Override
    public String getDescription() {
        return "联网搜索最新或需要外部核实的信息；由 Agent 自动判断何时使用，搜索参数由系统管理。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    public List<ToolCallback> callbacks() {
        return Arrays.asList(callbackProvider.getToolCallbacks());
    }
}
