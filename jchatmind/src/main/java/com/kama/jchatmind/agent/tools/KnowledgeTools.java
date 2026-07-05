package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.rag.RagSearchResponse;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.rag.RagContextRenderer;
import com.kama.jchatmind.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final RagContextRenderer ragContextRenderer;
    private final Set<String> allowedKnowledgeBaseIds;

    @Autowired
    public KnowledgeTools(RagService ragService, RagContextRenderer ragContextRenderer) {
        this(ragService, ragContextRenderer, null);
    }

    private KnowledgeTools(
            RagService ragService,
            RagContextRenderer ragContextRenderer,
            Set<String> allowedKnowledgeBaseIds
    ) {
        this.ragService = ragService;
        this.ragContextRenderer = ragContextRenderer;
        this.allowedKnowledgeBaseIds = allowedKnowledgeBaseIds;
    }

    public KnowledgeTools scopedTo(Collection<KnowledgeBaseDTO> knowledgeBases) {
        Set<String> allowedIds = knowledgeBases == null
                ? Set.of()
                : knowledgeBases.stream()
                .map(KnowledgeBaseDTO::getId)
                .collect(Collectors.toSet());
        return new KnowledgeTools(ragService, ragContextRenderer, allowedIds);
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
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回命中的知识片段、来源文档、chunkId、distance、score 和使用规则。"
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

        if (allowedKnowledgeBaseIds != null && !allowedKnowledgeBaseIds.contains(kbsId)) {
            return "错误：当前 Agent 无权访问知识库 " + kbsId + "，请从当前可用知识库列表中选择有效 ID";
        }

        if (query == null || query.trim().isEmpty()) {
            return "错误：查询内容不能为空";
        }

        RagSearchResponse response = ragService.search(kbsId, query.trim());
        return ragContextRenderer.renderForTool(response);
    }
}
