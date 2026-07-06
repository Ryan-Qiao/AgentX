package com.kama.jchatmind.service;

import com.kama.jchatmind.model.rag.MarkdownChunk;

import java.util.List;

public interface MarkdownChunkingService {
    List<MarkdownChunk> chunk(String markdown, ChunkingContext context);

    record ChunkingContext(
            String documentTitle,
            String sourceFileType,
            String originalFileName,
            Boolean trustMarkdownHeadings
    ) {
        public ChunkingContext(String documentTitle, String sourceFileType, String originalFileName) {
            this(documentTitle, sourceFileType, originalFileName, null);
        }
    }
}
