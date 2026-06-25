package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.service.RagService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;

    public KnowledgeTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回与查询最相关的知识片段。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        // 参数校验
        if (kbsId == null || kbsId.trim().isEmpty()) {
            return "错误：知识库 ID 不能为空，请提供有效的知识库 ID";
        }

        // 校验是否为有效的 UUID 格式
        try {
            UUID.fromString(kbsId);
        } catch (IllegalArgumentException e) {
            return "错误：知识库 ID 格式无效（" + kbsId + "），这不是一个有效的 UUID，请检查可用的知识库列表";
        }

        if (query == null || query.trim().isEmpty()) {
            return "错误：查询内容不能为空";
        }

        List<String> strings = ragService.similaritySearch(kbsId, query);
        return String.join("\n", strings);
    }
}
