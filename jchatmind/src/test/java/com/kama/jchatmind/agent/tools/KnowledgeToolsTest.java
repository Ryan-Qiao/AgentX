package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.rag.RagSearchResponse;
import com.kama.jchatmind.rag.RagContextRenderer;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeToolsTest {
    private static final String ALLOWED_KB_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DENIED_KB_ID = "22222222-2222-2222-2222-222222222222";

    @Test
    void rejectsKnowledgeBaseOutsideCurrentAgentScope() {
        AtomicBoolean ragCalled = new AtomicBoolean(false);
        KnowledgeTools tools = new KnowledgeTools(stubRagService(ragCalled), new RagContextRenderer())
                .scopedTo(List.of(KnowledgeBaseDTO.builder().id(ALLOWED_KB_ID).build()));

        String response = tools.knowledgeQuery(DENIED_KB_ID, "RAG");

        assertThat(response).contains("当前 Agent 无权访问知识库");
        assertThat(ragCalled).isFalse();
    }

    @Test
    void allowsKnowledgeBaseInsideCurrentAgentScope() {
        AtomicBoolean ragCalled = new AtomicBoolean(false);
        KnowledgeTools tools = new KnowledgeTools(stubRagService(ragCalled), new RagContextRenderer())
                .scopedTo(List.of(KnowledgeBaseDTO.builder().id(ALLOWED_KB_ID).build()));

        String response = tools.knowledgeQuery(ALLOWED_KB_ID, "RAG");

        assertThat(response).contains("检索问题：RAG");
        assertThat(ragCalled).isTrue();
    }

    private RagService stubRagService(AtomicBoolean called) {
        return new RagService() {
            @Override
            public float[] embed(String text) {
                return new float[0];
            }

            @Override
            public RagSearchResponse search(String kbId, String query) {
                called.set(true);
                return RagSearchResponse.builder()
                        .knowledgeBaseId(kbId)
                        .query(query)
                        .finalTopK(3)
                        .results(List.of())
                        .build();
            }

            @Override
            public List<String> similaritySearch(String kbId, String title) {
                return List.of();
            }
        };
    }
}
