package com.kama.jchatmind.model.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagSearchResponse {
    private String knowledgeBaseId;
    private String query;
    private Integer rawTopK;
    private Integer finalTopK;
    private Double maxDistance;
    private Double minRerankScore;
    private List<RagSearchResult> results;

    public List<RagSearchResult> selectedResults() {
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .filter(result -> !Boolean.TRUE.equals(result.getFiltered()))
                .limit(finalTopK == null ? results.size() : finalTopK)
                .toList();
    }
}
