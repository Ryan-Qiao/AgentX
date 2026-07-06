package com.kama.jchatmind.model.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MarkdownChunk {
    private String content;
    private String embeddingText;
    private String sectionTitle;
    private List<String> headingPath;
    private Integer headingLevel;
    private Integer chunkIndex;
    private Integer sectionChunkIndex;
    private Integer charStart;
    private Integer charEnd;
}
