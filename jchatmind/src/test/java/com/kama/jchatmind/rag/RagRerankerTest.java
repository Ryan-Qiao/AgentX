package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagRerankerTest {
    private final RagReranker reranker = new RagReranker();

    @Test
    void reranksCandidatesByHybridVectorAndLexicalScore() {
        List<RagSearchResult> reranked = reranker.rerank(
                "Agent trace 行为监测",
                List.of(
                        result("c1", " unrelated weather content", 0.90),
                        result("c2", "Agent trace records tool calls and monitors behavior", 0.70)
                ),
                0.2,
                0.8
        );

        assertThat(reranked.get(0).getChunkId()).isEqualTo("c2");
        assertThat(reranked.get(0).getLexicalScore()).isGreaterThan(reranked.get(1).getLexicalScore());
        assertThat(reranked.get(0).getRerankScore()).isGreaterThan(reranked.get(1).getRerankScore());
    }

    @Test
    void lexicalScoreSupportsChineseCharactersAndBigrams() {
        double score = reranker.lexicalScore("知识库召回质量", "RAG 知识库召回质量需要记录 distance 和来源文档");

        assertThat(score).isGreaterThan(0.5);
    }

    private RagSearchResult result(String chunkId, String content, Double score) {
        return RagSearchResult.builder()
                .chunkId(chunkId)
                .documentTitle("test.md")
                .content(content)
                .score(score)
                .build();
    }
}
