package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.rag.MarkdownChunk;
import com.kama.jchatmind.service.MarkdownChunkingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkingServiceImplTest {
    @Test
    void chunksMarkdownByHeadingPathAndSplitsLongSectionsWithOverlap() {
        MarkdownChunkingServiceImpl chunkingService = new MarkdownChunkingServiceImpl();
        ReflectionTestUtils.setField(chunkingService, "maxChars", 30);
        ReflectionTestUtils.setField(chunkingService, "overlapChars", 5);
        ReflectionTestUtils.setField(chunkingService, "minChars", 0);

        String markdown = """
                # MySQL

                这份文档介绍 MySQL。

                ## 索引

                索引可以提高查询效率。

                ### 索引下推

                abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ

                ## 事务

                事务具有 ACID 四大特性。
                """;

        List<MarkdownChunk> chunks = chunkingService.chunk(
                markdown,
                new MarkdownChunkingService.ChunkingContext("mysql-note.md", "md", "mysql-note.md")
        );

        assertThat(chunks).hasSize(6);

        assertThat(chunks.get(0).getSectionTitle()).isEqualTo("MySQL");
        assertThat(chunks.get(0).getHeadingPath()).containsExactly("MySQL");

        assertThat(chunks.get(1).getSectionTitle()).isEqualTo("索引");
        assertThat(chunks.get(1).getHeadingPath()).containsExactly("MySQL", "索引");

        List<MarkdownChunk> pushdownChunks = chunks.stream()
                .filter(chunk -> "索引下推".equals(chunk.getSectionTitle()))
                .toList();
        assertThat(pushdownChunks).hasSize(3);
        assertThat(pushdownChunks)
                .allSatisfy(chunk -> assertThat(chunk.getHeadingPath())
                        .containsExactly("MySQL", "索引", "索引下推"));
        assertThat(pushdownChunks).extracting(MarkdownChunk::getSectionChunkIndex)
                .containsExactly(1, 2, 3);
        assertThat(pushdownChunks).extracting(MarkdownChunk::getCharStart)
                .containsExactly(0, 25, 50);
        assertThat(pushdownChunks).extracting(MarkdownChunk::getCharEnd)
                .containsExactly(30, 55, 62);
        assertThat(pushdownChunks.get(0).getEmbeddingText())
                .contains("文档：mysql-note.md")
                .contains("章节：MySQL > 索引 > 索引下推");

        MarkdownChunk transactionChunk = chunks.get(5);
        assertThat(transactionChunk.getSectionTitle()).isEqualTo("事务");
        assertThat(transactionChunk.getHeadingPath()).containsExactly("MySQL", "事务");
    }

    @Test
    void markdownModeKeepsPreambleAndDoesNotPadSkippedHeadingLevels() {
        MarkdownChunkingServiceImpl chunkingService = new MarkdownChunkingServiceImpl();
        ReflectionTestUtils.setField(chunkingService, "maxChars", 1200);
        ReflectionTestUtils.setField(chunkingService, "overlapChars", 150);
        ReflectionTestUtils.setField(chunkingService, "minChars", 80);

        String markdown = """
                文档开头介绍。

                ### 跳级标题

                跳级标题正文。

                #### 空标题

                ### 第二个标题

                第二段正文。
                """;

        List<MarkdownChunk> chunks = chunkingService.chunk(
                markdown,
                new MarkdownChunkingService.ChunkingContext("doc.md", "md", "doc.md")
        );

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getSectionTitle()).isEqualTo("doc.md");
        assertThat(chunks.get(0).getHeadingPath()).containsExactly("doc.md");
        assertThat(chunks.get(0).getContent()).isEqualTo("文档开头介绍。");

        assertThat(chunks.get(1).getSectionTitle()).isEqualTo("跳级标题");
        assertThat(chunks.get(1).getHeadingPath()).containsExactly("跳级标题");

        assertThat(chunks.get(2).getSectionTitle()).isEqualTo("第二个标题");
        assertThat(chunks.get(2).getHeadingPath()).containsExactly("第二个标题");
    }

    @Test
    void nonMarkdownModeIgnoresConvertedHeadingsAndChunksWholeDocumentAsPlainText() {
        MarkdownChunkingServiceImpl chunkingService = new MarkdownChunkingServiceImpl();
        ReflectionTestUtils.setField(chunkingService, "maxChars", 30);
        ReflectionTestUtils.setField(chunkingService, "overlapChars", 5);
        ReflectionTestUtils.setField(chunkingService, "minChars", 0);

        String convertedPdfMarkdown = """
                PDF 开头正文。

                ## -p 指定密码，这其实是 PDF 转换出来的伪标题

                连接器会验证用户名和密码，然后读取权限。
                """;

        List<MarkdownChunk> chunks = chunkingService.chunk(
                convertedPdfMarkdown,
                new MarkdownChunkingService.ChunkingContext("mysql.pdf", "pdf", "mysql.pdf")
        );

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
                .allSatisfy(chunk -> {
                    assertThat(chunk.getSectionTitle()).isEqualTo("mysql.pdf");
                    assertThat(chunk.getHeadingPath()).containsExactly("mysql.pdf");
                    assertThat(chunk.getHeadingLevel()).isEqualTo(1);
                });
        assertThat(chunks.get(0).getContent()).contains("## -p 指定密码");
        assertThat(chunks).extracting(MarkdownChunk::getCharStart)
                .contains(0, 25);
    }
}
