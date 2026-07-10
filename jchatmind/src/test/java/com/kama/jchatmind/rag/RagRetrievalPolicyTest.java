package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrievalPolicyTest {
    private final RagRetrievalPolicy policy = new RagRetrievalPolicy();

    @Test
    void appliesDistanceDuplicateDocumentLimitAndFinalTopKFilters() {
        List<RagSearchResult> results = policy.apply(List.of(
                        result("c1", "d1", "alpha content", 0.10),
                        result("c2", "d1", "beta content", 0.20),
                        result("c3", "d1", "gamma content", 0.30),
                        result("c4", "d2", "alpha   content", 0.15),
                        result("c5", "d3", "", 0.05),
                        result("c6", "d4", "delta content", 0.90),
                        result("c7", "d5", "epsilon content", 0.25),
                        result("c8", "d6", "low rerank content", 0.10, 0.90)
                ),
                2,
                0.50,
                1.0,
                2
        );

        assertThat(results).extracting(RagSearchResult::getChunkId)
                .containsExactly("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8");

        assertThat(results.get(0).getFiltered()).isFalse();
        assertThat(results.get(0).getRank()).isEqualTo(1);
        assertThat(results.get(1).getFiltered()).isFalse();
        assertThat(results.get(1).getRank()).isEqualTo(2);

        assertThat(results.get(2).getFiltered()).isTrue();
        assertThat(results.get(2).getFilterReason()).isEqualTo("too_many_chunks_from_same_document");
        assertThat(results.get(3).getFiltered()).isTrue();
        assertThat(results.get(3).getFilterReason()).isEqualTo("duplicate_content");
        assertThat(results.get(4).getFiltered()).isTrue();
        assertThat(results.get(4).getFilterReason()).isEqualTo("empty_content");
        assertThat(results.get(5).getFiltered()).isTrue();
        assertThat(results.get(5).getFilterReason()).isEqualTo("distance_gt_max");
        assertThat(results.get(6).getFiltered()).isTrue();
        assertThat(results.get(6).getFilterReason()).isEqualTo("exceeds_final_top_k");
        assertThat(results.get(7).getFiltered()).isTrue();
        assertThat(results.get(7).getFilterReason()).isEqualTo("rerank_score_lt_min");
    }

    private RagSearchResult result(String chunkId, String documentId, String content, Double distance) {
        return result(chunkId, documentId, content, distance, 2.0);
    }

    private RagSearchResult result(
            String chunkId,
            String documentId,
            String content,
            Double distance,
            Double rerankScore
    ) {
        return RagSearchResult.builder()
                .chunkId(chunkId)
                .documentId(documentId)
                .documentTitle(documentId + ".md")
                .content(content)
                .distance(distance)
                .score(distance == null ? null : 1.0 / (1.0 + distance))
                .rerankScore(rerankScore)
                .build();
    }
}
