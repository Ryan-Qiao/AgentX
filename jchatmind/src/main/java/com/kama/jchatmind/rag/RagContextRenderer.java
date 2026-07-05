package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResponse;
import com.kama.jchatmind.model.rag.RagSearchResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class RagContextRenderer {
    public String renderForTool(RagSearchResponse response) {
        List<RagSearchResult> selectedResults = response.selectedResults();
        if (selectedResults.isEmpty()) {
            return """
                    检索问题：%s

                    知识库中没有找到与当前问题足够相关的内容。
                    请不要基于知识库编造答案，可以基于通用知识回答并说明未找到知识库依据。
                    """.formatted(response.getQuery());
        }

        String chunks = selectedResults.stream()
                .map(this::renderChunk)
                .collect(Collectors.joining("\n\n"));

        return """
                检索问题：%s

                命中的知识片段：
                %s

                使用规则：
                - 只能基于以上片段回答知识库相关内容。
                - 如果片段不足以回答，请说明知识库没有找到足够相关的信息。
                - 不要编造片段中没有的内容。
                - 回答中引用知识库依据时，请使用片段编号，例如 [1]、[2]。
                """.formatted(response.getQuery(), chunks);
    }

    private String renderChunk(RagSearchResult result) {
        String documentTitle = StringUtils.hasText(result.getDocumentTitle())
                ? result.getDocumentTitle()
                : "未知文档";
        String chunkId = StringUtils.hasText(result.getChunkId()) ? result.getChunkId() : "unknown";
        String distance = result.getDistance() == null
                ? "unknown"
                : String.format(Locale.ROOT, "%.4f", result.getDistance());
        String score = result.getScore() == null
                ? "unknown"
                : String.format(Locale.ROOT, "%.4f", result.getScore());

        return """
                [%d] 文档：%s
                documentId：%s
                chunkId：%s
                distance：%s
                score：%s
                内容：
                %s
                """.formatted(
                result.getRank(),
                documentTitle,
                result.getDocumentId(),
                chunkId,
                distance,
                score,
                result.getContent()
        ).trim();
    }
}
