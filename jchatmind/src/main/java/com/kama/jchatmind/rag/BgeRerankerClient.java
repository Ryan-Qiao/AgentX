package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class BgeRerankerClient {
    private final WebClient.Builder webClientBuilder;
    private final RagReranker hybridReranker;

    public BgeRerankerClient(WebClient.Builder webClientBuilder, RagReranker hybridReranker) {
        this.webClientBuilder = webClientBuilder;
        this.hybridReranker = hybridReranker;
    }

    public List<RagSearchResult> rerank(
            String query,
            List<RagSearchResult> candidates,
            String endpoint,
            String model,
            int timeoutMs
    ) {
        if (!StringUtils.hasText(query) || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        Assert.hasText(endpoint, "BGE reranker endpoint cannot be empty");

        List<String> passages = candidates.stream()
                .map(this::passageText)
                .toList();

        BgeRerankResponse response = webClientBuilder.build()
                .post()
                .uri(endpoint)
                .bodyValue(new BgeRerankRequest(model, query, passages))
                .retrieve()
                .bodyToMono(BgeRerankResponse.class)
                .block(Duration.ofMillis(timeoutMs));

        Assert.notNull(response, "BGE reranker response cannot be null");

        List<Double> scores = normalizeScores(response, candidates.size());
        List<RagSearchResult> reranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RagSearchResult candidate = candidates.get(i);
            reranked.add(candidate.toBuilder()
                    .lexicalScore(hybridReranker.lexicalScore(query, passageText(candidate)))
                    .rerankScore(scores.get(i))
                    .build());
        }

        reranked.sort(Comparator.comparing(
                (RagSearchResult result) -> result.getRerankScore() == null ? Double.NEGATIVE_INFINITY : result.getRerankScore()
        ).reversed());
        return reranked;
    }

    private List<Double> normalizeScores(BgeRerankResponse response, int expectedSize) {
        if (response.getScores() != null && response.getScores().size() == expectedSize) {
            return response.getScores();
        }

        if (response.getResults() != null && !response.getResults().isEmpty()) {
            List<Double> scores = new ArrayList<>(Collections.nCopies(expectedSize, Double.NEGATIVE_INFINITY));
            for (BgeRerankResult result : response.getResults()) {
                if (result.getIndex() != null
                        && result.getIndex() >= 0
                        && result.getIndex() < expectedSize
                        && result.getScore() != null) {
                    scores.set(result.getIndex(), result.getScore());
                }
            }
            if (scores.stream().anyMatch(score -> score != Double.NEGATIVE_INFINITY)) {
                return scores;
            }
        }

        throw new IllegalStateException("BGE reranker response must contain scores or indexed results");
    }

    private String passageText(RagSearchResult result) {
        return "%s\n%s\n%s".formatted(
                result.getDocumentTitle() == null ? "" : result.getDocumentTitle(),
                result.getMetadata() == null ? "" : result.getMetadata(),
                result.getContent() == null ? "" : result.getContent()
        );
    }

    private record BgeRerankRequest(String model, String query, List<String> passages) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BgeRerankResponse {
        private List<Double> scores;
        private List<BgeRerankResult> results;
        private Map<String, Object> usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BgeRerankResult {
        private Integer index;
        private Double score;
    }
}
