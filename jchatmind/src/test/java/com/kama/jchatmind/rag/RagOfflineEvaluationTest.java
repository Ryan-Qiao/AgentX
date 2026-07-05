package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagOfflineEvaluationTest {
    private static final int TOP_K = 3;
    private final RagReranker reranker = new RagReranker();

    @Test
    void rerankImprovesOfflineRetrievalMetricsOnLabeledSet() {
        List<EvaluationCase> cases = List.of(
                evaluationCase(
                        "Agent trace 行为监测怎么做",
                        Set.of("trace-1", "trace-2"),
                        candidate("noise-1", "天气查询工具会返回城市天气。", 0.93),
                        candidate("noise-2", "用户记忆用于记录偏好。", 0.88),
                        candidate("trace-1", "Agent trace 需要记录工具调用、模型输出和行为监测事件。", 0.82),
                        candidate("noise-3", "数据库工具只能执行 SELECT。", 0.78),
                        candidate("trace-2", "trace 面板可以展示 Agent 行为链路和异常监控。", 0.74)
                ),
                evaluationCase(
                        "RAG 召回质量如何观测",
                        Set.of("rag-obs-1", "rag-obs-2"),
                        candidate("noise-4", "文件上传时需要保存原始路径。", 0.94),
                        candidate("noise-5", "SDKMAN 可以管理多个 JDK。", 0.90),
                        candidate("rag-obs-1", "RAG 召回可观测需要记录 query、chunkId、documentId 和 distance。", 0.81),
                        candidate("noise-6", "前端使用 SSE 接收消息。", 0.79),
                        candidate("rag-obs-2", "检索日志应包含 score、过滤状态和最终注入片段。", 0.70)
                ),
                evaluationCase(
                        "MarkItDown 支持哪些文档转换",
                        Set.of("convert-1", "convert-2"),
                        candidate("noise-7", "PostgreSQL 使用 pgvector 存储 embedding。", 0.92),
                        candidate("noise-8", "Agent 名称是基础身份。", 0.89),
                        candidate("convert-1", "MarkItDown 可以把 PDF、DOCX、PPTX、XLSX 转为 Markdown。", 0.80),
                        candidate("noise-9", "天气工具不应用于普通城市介绍。", 0.75),
                        candidate("convert-2", "多格式文档转换后统一进入 Markdown 清洗、分块和 embedding 流程。", 0.69)
                ),
                evaluationCase(
                        "知识库不足时怎么回答",
                        Set.of("faith-1", "faith-2"),
                        candidate("noise-10", "模型返回为空时可以重试。", 0.91),
                        candidate("noise-11", "SSE 是服务端到前端的单向推送。", 0.87),
                        candidate("faith-1", "如果知识片段不足以回答，必须说明知识库没有足够信息。", 0.79),
                        candidate("noise-12", "PostgreSQL 服务可通过 brew services 启动。", 0.73),
                        candidate("faith-2", "不要把通用知识伪装成知识库内容，补充时需要明确区分。", 0.68)
                ),
                evaluationCase(
                        "chunk embedding 应该包含什么",
                        Set.of("chunk-1", "chunk-2"),
                        candidate("noise-13", "前端创建 Agent 时需要解释名称作用。", 0.90),
                        candidate("noise-14", "工具调用结果会保存为 tool 消息。", 0.86),
                        candidate("chunk-1", "chunk embedding 内容应包含标题、正文关键内容和 headingPath。", 0.78),
                        candidate("noise-15", "数据库只读工具需要拒绝 DELETE。", 0.72),
                        candidate("chunk-2", "只 embedding section title 会降低召回质量，应加入正文内容。", 0.66)
                )
        );

        Metrics vectorOnly = evaluate(cases, false);
        Metrics withRerank = evaluate(cases, true);

        assertThat(vectorOnly.precisionAtK()).isEqualTo(0.3333333333333333);
        assertThat(vectorOnly.recallAtK()).isEqualTo(0.5);
        assertThat(vectorOnly.hitRateAt1()).isEqualTo(0.0);

        assertThat(withRerank.precisionAtK()).isEqualTo(0.6666666666666666);
        assertThat(withRerank.recallAtK()).isEqualTo(1.0);
        assertThat(withRerank.hitRateAt1()).isEqualTo(1.0);
        assertThat(withRerank.mrr()).isEqualTo(1.0);
    }

    private Metrics evaluate(List<EvaluationCase> cases, boolean useRerank) {
        int totalRetrieved = 0;
        int relevantRetrieved = 0;
        int totalRelevant = 0;
        int top1Hits = 0;
        double reciprocalRankSum = 0.0;

        for (EvaluationCase evaluationCase : cases) {
            List<RagSearchResult> ranked = new ArrayList<>(evaluationCase.candidates());
            ranked.sort(Comparator.comparing(RagSearchResult::getScore).reversed());
            if (useRerank) {
                ranked = reranker.rerank(evaluationCase.query(), ranked, 0.2, 0.8);
            }

            List<RagSearchResult> topK = ranked.stream().limit(TOP_K).toList();
            totalRetrieved += topK.size();
            totalRelevant += evaluationCase.relevantChunkIds().size();
            relevantRetrieved += (int) topK.stream()
                    .filter(result -> evaluationCase.relevantChunkIds().contains(result.getChunkId()))
                    .count();
            if (!topK.isEmpty() && evaluationCase.relevantChunkIds().contains(topK.get(0).getChunkId())) {
                top1Hits++;
            }
            reciprocalRankSum += reciprocalRank(ranked, evaluationCase.relevantChunkIds());
        }

        return new Metrics(
                (double) relevantRetrieved / totalRetrieved,
                (double) relevantRetrieved / totalRelevant,
                (double) top1Hits / cases.size(),
                reciprocalRankSum / cases.size()
        );
    }

    private double reciprocalRank(List<RagSearchResult> ranked, Set<String> relevantChunkIds) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevantChunkIds.contains(ranked.get(i).getChunkId())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private EvaluationCase evaluationCase(String query, Set<String> relevantChunkIds, RagSearchResult... candidates) {
        return new EvaluationCase(query, relevantChunkIds, List.of(candidates));
    }

    private RagSearchResult candidate(String chunkId, String content, double vectorScore) {
        return RagSearchResult.builder()
                .chunkId(chunkId)
                .documentId("doc-" + chunkId)
                .documentTitle(chunkId + ".md")
                .content(content)
                .score(vectorScore)
                .build();
    }

    private record EvaluationCase(String query, Set<String> relevantChunkIds, List<RagSearchResult> candidates) {
    }

    private record Metrics(double precisionAtK, double recallAtK, double hitRateAt1, double mrr) {
    }
}
