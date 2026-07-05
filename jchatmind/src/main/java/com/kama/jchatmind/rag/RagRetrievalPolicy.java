package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RagRetrievalPolicy {
    public List<RagSearchResult> apply(
            List<RagSearchResult> rawResults,
            int finalTopK,
            Double maxDistance,
            int maxChunksPerDocument
    ) {
        List<RagSearchResult> processed = new ArrayList<>();
        Set<String> seenContent = new HashSet<>();
        Map<String, Integer> documentCounts = new HashMap<>();
        int selectedRank = 1;

        for (RagSearchResult rawResult : rawResults) {
            RagSearchResult.RagSearchResultBuilder builder = rawResult.toBuilder();
            String filterReason = filterReason(rawResult, seenContent, documentCounts, maxDistance, maxChunksPerDocument);

            if (filterReason == null && selectedRank <= finalTopK) {
                builder.filtered(false).filterReason(null).rank(selectedRank);
                selectedRank++;
                seenContent.add(normalizeContent(rawResult.getContent()));
                documentCounts.merge(rawResult.getDocumentId(), 1, Integer::sum);
            } else {
                builder.filtered(true)
                        .filterReason(filterReason != null ? filterReason : "exceeds_final_top_k")
                        .rank(null);
            }

            processed.add(builder.build());
        }

        return processed;
    }

    private String filterReason(
            RagSearchResult result,
            Set<String> seenContent,
            Map<String, Integer> documentCounts,
            Double maxDistance,
            int maxChunksPerDocument
    ) {
        if (!StringUtils.hasText(result.getContent())) {
            return "empty_content";
        }
        if (maxDistance != null && result.getDistance() != null && result.getDistance() > maxDistance) {
            return "distance_gt_max";
        }
        String normalizedContent = normalizeContent(result.getContent());
        if (seenContent.contains(normalizedContent)) {
            return "duplicate_content";
        }
        if (result.getDocumentId() != null
                && documentCounts.getOrDefault(result.getDocumentId(), 0) >= maxChunksPerDocument) {
            return "too_many_chunks_from_same_document";
        }
        return null;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("\\s+", " ").trim();
    }
}
