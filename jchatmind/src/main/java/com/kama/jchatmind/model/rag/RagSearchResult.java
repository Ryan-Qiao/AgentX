package com.kama.jchatmind.model.rag;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class RagSearchResult {
    private String chunkId;
    private String knowledgeBaseId;
    private String documentId;
    private String documentTitle;
    private String content;
    private String metadata;
    private Double distance;
    private Double score;
    private Boolean filtered;
    private String filterReason;
    private Integer rank;
}
