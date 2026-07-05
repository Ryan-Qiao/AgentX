package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResponse;
import com.kama.jchatmind.model.rag.RagSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagContextRendererTest {
    private final RagContextRenderer renderer = new RagContextRenderer();

    @Test
    void rendersStructuredToolContextWithCitationRules() {
        RagSearchResponse response = RagSearchResponse.builder()
                .knowledgeBaseId("kb-1")
                .query("什么是 JChatMind RAG？")
                .rawTopK(10)
                .finalTopK(3)
                .results(List.of(
                        RagSearchResult.builder()
                                .rank(1)
                                .chunkId("chunk-1")
                                .documentId("doc-1")
                                .documentTitle("rag.md")
                                .content("JChatMind RAG 使用知识库片段增强回答。")
                                .distance(0.41234)
                                .score(0.707)
                                .filtered(false)
                                .build()
                ))
                .build();

        String rendered = renderer.renderForTool(response);

        assertThat(rendered).contains("检索问题：什么是 JChatMind RAG？");
        assertThat(rendered).contains("[1] 文档：rag.md");
        assertThat(rendered).contains("chunkId：chunk-1");
        assertThat(rendered).contains("distance：0.4123");
        assertThat(rendered).contains("请使用片段编号");
    }

    @Test
    void rendersNoEvidenceMessageWhenNoSelectedResults() {
        RagSearchResponse response = RagSearchResponse.builder()
                .query("不存在的问题")
                .finalTopK(3)
                .results(List.of(
                        RagSearchResult.builder()
                                .content("不相关")
                                .filtered(true)
                                .filterReason("distance_gt_max")
                                .build()
                ))
                .build();

        String rendered = renderer.renderForTool(response);

        assertThat(rendered).contains("知识库中没有找到与当前问题足够相关的内容");
        assertThat(rendered).contains("请不要基于知识库编造答案");
    }
}
