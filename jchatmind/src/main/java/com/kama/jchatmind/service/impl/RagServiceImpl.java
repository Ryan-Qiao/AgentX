package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.rag.RagSearchResponse;
import com.kama.jchatmind.model.rag.RagSearchResult;
import com.kama.jchatmind.rag.BgeRerankerClient;
import com.kama.jchatmind.rag.RagReranker;
import com.kama.jchatmind.rag.RagRetrievalPolicy;
import com.kama.jchatmind.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagServiceImpl implements RagService {

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final RagRetrievalPolicy ragRetrievalPolicy;
    private final RagReranker ragReranker;
    private final BgeRerankerClient bgeRerankerClient;

    @Value("${rag.retrieval.raw-top-k:20}")
    private int rawTopK;

    @Value("${rag.retrieval.final-top-k:3}")
    private int finalTopK;

    @Value("${rag.retrieval.max-distance:}")
    private Double maxDistance;

    @Value("${rag.retrieval.max-chunks-per-document:2}")
    private int maxChunksPerDocument;

    @Value("${rag.retrieval.debug-enabled:true}")
    private boolean debugEnabled;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.strategy:bge-reranker}")
    private String rerankStrategy;

    @Value("${rag.rerank.vector-weight:0.70}")
    private double vectorWeight;

    @Value("${rag.rerank.lexical-weight:0.30}")
    private double lexicalWeight;

    @Value("${rag.rerank.bge.endpoint:http://localhost:8001/rerank}")
    private String bgeRerankerEndpoint;

    @Value("${rag.rerank.bge.model:BAAI/bge-reranker-v2-m3}")
    private String bgeRerankerModel;

    @Value("${rag.rerank.bge.timeout-ms:3000}")
    private int bgeRerankerTimeoutMs;

    @Value("${rag.rerank.fallback-enabled:true}")
    private boolean rerankFallbackEnabled;

    public RagServiceImpl(
            WebClient.Builder builder,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            RagRetrievalPolicy ragRetrievalPolicy,
            RagReranker ragReranker,
            BgeRerankerClient bgeRerankerClient
    ) {
        this.webClient = builder.baseUrl("http://localhost:11434").build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.ragRetrievalPolicy = ragRetrievalPolicy;
        this.ragReranker = ragReranker;
        this.bgeRerankerClient = bgeRerankerClient;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public RagSearchResponse search(String kbId, String query) {
        String queryEmbedding = toPgVector(doEmbed(query));
        List<RagSearchResult> rawResults = chunkBgeM3Mapper.similaritySearchDetailed(kbId, queryEmbedding, rawTopK);
        if (rerankEnabled) {
            rawResults = rerank(query, rawResults);
        }
        List<RagSearchResult> processedResults = ragRetrievalPolicy.apply(
                rawResults,
                finalTopK,
                maxDistance,
                maxChunksPerDocument
        );

        RagSearchResponse response = RagSearchResponse.builder()
                .knowledgeBaseId(kbId)
                .query(query)
                .rawTopK(rawTopK)
                .finalTopK(finalTopK)
                .maxDistance(maxDistance)
                .results(processedResults)
                .build();

        logRetrieval(response);
        return response;
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return search(kbId, title).selectedResults()
                .stream()
                .map(RagSearchResult::getContent)
                .toList();
    }

    private void logRetrieval(RagSearchResponse response) {
        if (!debugEnabled) {
            return;
        }
        String results = response.getResults()
                .stream()
                .map(result -> "chunkId=%s, documentId=%s, distance=%s, score=%s, lexicalScore=%s, rerankScore=%s, filtered=%s, filterReason=%s"
                        .formatted(
                                result.getChunkId(),
                                result.getDocumentId(),
                                result.getDistance(),
                                result.getScore(),
                                result.getLexicalScore(),
                                result.getRerankScore(),
                                result.getFiltered(),
                                result.getFilterReason()
                        ))
                .collect(Collectors.joining("\n"));
        log.info("""

                ========== RAG Retrieval ==========
                kbId={}
                query={}
                rawTopK={}
                finalTopK={}
                maxDistance={}
                rerankEnabled={}
                rerankStrategy={}
                results:
                {}
                ===================================
                """,
                response.getKnowledgeBaseId(),
                response.getQuery(),
                response.getRawTopK(),
                response.getFinalTopK(),
                response.getMaxDistance(),
                rerankEnabled,
                rerankStrategy,
                results);
    }

    private List<RagSearchResult> rerank(String query, List<RagSearchResult> rawResults) {
        if ("none".equalsIgnoreCase(rerankStrategy)) {
            return rawResults;
        }

        if ("hybrid".equalsIgnoreCase(rerankStrategy)) {
            return ragReranker.rerank(query, rawResults, vectorWeight, lexicalWeight);
        }

        if ("bge-reranker".equalsIgnoreCase(rerankStrategy)) {
            try {
                return bgeRerankerClient.rerank(
                        query,
                        rawResults,
                        bgeRerankerEndpoint,
                        bgeRerankerModel,
                        bgeRerankerTimeoutMs
                );
            } catch (Exception e) {
                if (!rerankFallbackEnabled) {
                    throw e;
                }
                log.warn("BGE reranker failed, fallback to hybrid rerank. endpoint={}, model={}",
                        bgeRerankerEndpoint,
                        bgeRerankerModel,
                        e);
                return ragReranker.rerank(query, rawResults, vectorWeight, lexicalWeight);
            }
        }

        log.warn("Unknown RAG rerank strategy: {}, fallback to hybrid rerank", rerankStrategy);
        return ragReranker.rerank(query, rawResults, vectorWeight, lexicalWeight);
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
