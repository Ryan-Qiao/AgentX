package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.rag.MarkdownChunk;
import com.kama.jchatmind.service.MarkdownChunkingService;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

@Service
public class MarkdownChunkingServiceImpl implements MarkdownChunkingService {
    private final Parser parser;

    @Value("${rag.chunking.max-chars:1200}")
    private int maxChars;

    @Value("${rag.chunking.overlap-chars:150}")
    private int overlapChars;

    @Value("${rag.chunking.min-chars:80}")
    private int minChars;

    private String originalMarkdownContent;

    public MarkdownChunkingServiceImpl() {
        MutableDataSet options = new MutableDataSet();
        this.parser = Parser.builder(options).build();
    }

    @Override
    public List<MarkdownChunk> chunk(String markdown, ChunkingContext context) {
        if (!StringUtils.hasText(markdown)) {
            return List.of();
        }
        if (!trustMarkdownHeadings(context)) {
            return chunkPlainText(markdown, context);
        }
        this.originalMarkdownContent = markdown;
        Document document = parser.parse(markdown);
        List<Node> topLevelNodes = topLevelNodes(document);
        List<Section> sections = extractSections(topLevelNodes, context);
        List<MarkdownChunk> chunks = new ArrayList<>();

        for (Section section : sections) {
            if (!StringUtils.hasText(section.title()) || !StringUtils.hasText(section.content())) {
                continue;
            }
            List<TextSlice> slices = splitSection(section.content());
            for (int i = 0; i < slices.size(); i++) {
                TextSlice slice = slices.get(i);
                chunks.add(MarkdownChunk.builder()
                        .content(slice.text())
                        .embeddingText(buildEmbeddingText(context, section.headingPath(), slice.text()))
                        .sectionTitle(section.title())
                        .headingPath(section.headingPath())
                        .headingLevel(section.headingLevel())
                        .chunkIndex(chunks.size())
                        .sectionChunkIndex(i + 1)
                        .charStart(slice.start())
                        .charEnd(slice.end())
                        .build());
            }
        }
        return chunks;
    }

    private List<MarkdownChunk> chunkPlainText(String text, ChunkingContext context) {
        String documentTitle = context != null && StringUtils.hasText(context.documentTitle())
                ? context.documentTitle()
                : "未命名文档";
        String normalizedText = text.trim();
        List<TextSlice> slices = splitSection(normalizedText);
        List<MarkdownChunk> chunks = new ArrayList<>();
        for (int i = 0; i < slices.size(); i++) {
            TextSlice slice = slices.get(i);
            chunks.add(MarkdownChunk.builder()
                    .content(slice.text())
                    .embeddingText(buildEmbeddingText(context, List.of(documentTitle), slice.text()))
                    .sectionTitle(documentTitle)
                    .headingPath(List.of(documentTitle))
                    .headingLevel(1)
                    .chunkIndex(i)
                    .sectionChunkIndex(i + 1)
                    .charStart(slice.start())
                    .charEnd(slice.end())
                    .build());
        }
        return chunks;
    }

    private boolean trustMarkdownHeadings(ChunkingContext context) {
        if (context != null && context.trustMarkdownHeadings() != null) {
            return context.trustMarkdownHeadings();
        }
        String sourceFileType = context == null ? "" : context.sourceFileType();
        String normalizedType = sourceFileType == null
                ? ""
                : sourceFileType.trim().toLowerCase(Locale.ROOT).replace(".", "");
        return "md".equals(normalizedType) || "markdown".equals(normalizedType);
    }

    private List<Node> topLevelNodes(Document document) {
        List<Node> nodes = new ArrayList<>();
        Node child = document.getFirstChild();
        while (child != null) {
            nodes.add(child);
            child = child.getNext();
        }
        return nodes;
    }

    private List<Section> extractSections(List<Node> topLevelNodes, ChunkingContext context) {
        List<Section> sections = new ArrayList<>();
        TreeMap<Integer, String> headingStack = new TreeMap<>();

        String preamble = collectPreambleContent(topLevelNodes);
        if (StringUtils.hasText(preamble)) {
            String documentTitle = context != null && StringUtils.hasText(context.documentTitle())
                    ? context.documentTitle()
                    : "文档开头";
            sections.add(new Section(documentTitle, 1, List.of(documentTitle), preamble));
        }

        for (int i = 0; i < topLevelNodes.size(); i++) {
            Node node = topLevelNodes.get(i);
            if (!(node instanceof Heading heading)) {
                continue;
            }

            String title = extractHeadingText(heading);
            if (!StringUtils.hasText(title)) {
                continue;
            }

            int level = heading.getLevel();
            updateHeadingStack(headingStack, level, title);
            String content = collectSectionContent(topLevelNodes, i);
            sections.add(new Section(title, level, List.copyOf(headingStack.values()), content));
        }
        return sections;
    }

    private void updateHeadingStack(TreeMap<Integer, String> headingStack, int level, String title) {
        List<Integer> staleLevels = headingStack.keySet()
                .stream()
                .filter(existingLevel -> existingLevel >= level)
                .toList();
        staleLevels.forEach(headingStack::remove);
        headingStack.put(level, title);
    }

    private String collectPreambleContent(List<Node> topLevelNodes) {
        StringBuilder contentBuilder = new StringBuilder();
        for (Node node : topLevelNodes) {
            if (node instanceof Heading) {
                break;
            }
            String content = extractNodeContent(node);
            if (StringUtils.hasText(content)) {
                if (contentBuilder.length() > 0) {
                    contentBuilder.append("\n");
                }
                contentBuilder.append(content);
            }
        }
        return contentBuilder.toString().trim();
    }

    private String collectSectionContent(List<Node> topLevelNodes, int headingIndex) {
        StringBuilder contentBuilder = new StringBuilder();
        for (int j = headingIndex + 1; j < topLevelNodes.size(); j++) {
            Node nextNode = topLevelNodes.get(j);
            if (nextNode instanceof Heading) {
                break;
            }
            String content = extractNodeContent(nextNode);
            if (StringUtils.hasText(content)) {
                if (contentBuilder.length() > 0) {
                    contentBuilder.append("\n");
                }
                contentBuilder.append(content);
            }
        }
        return contentBuilder.toString().trim();
    }

    private List<TextSlice> splitSection(String content) {
        String normalizedContent = StringUtils.hasText(content) ? content.trim() : "";
        if (normalizedContent.length() <= maxChars) {
            return List.of(new TextSlice(normalizedContent, 0, normalizedContent.length()));
        }

        int effectiveMaxChars = Math.max(1, maxChars);
        int effectiveOverlap = Math.max(0, Math.min(overlapChars, effectiveMaxChars - 1));
        List<TextSlice> slices = new ArrayList<>();
        int start = 0;
        while (start < normalizedContent.length()) {
            int end = Math.min(start + effectiveMaxChars, normalizedContent.length());
            if (normalizedContent.length() - end < minChars && !slices.isEmpty()) {
                end = normalizedContent.length();
            }
            slices.add(new TextSlice(normalizedContent.substring(start, end).trim(), start, end));
            if (end >= normalizedContent.length()) {
                break;
            }
            start = Math.max(end - effectiveOverlap, start + 1);
        }
        return slices;
    }

    private String buildEmbeddingText(
            ChunkingContext context,
            List<String> headingPath,
            String content
    ) {
        String documentTitle = context == null ? "" : context.documentTitle();
        String heading = headingPath.stream()
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " > " + right)
                .orElse("");
        return """
                文档：%s
                章节：%s

                %s
                """.formatted(documentTitle, heading, content).trim();
    }

    private String extractHeadingText(Heading heading) {
        StringBuilder text = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            String childText = extractPlainText(child);
            if (StringUtils.hasText(childText)) {
                if (text.length() > 0) {
                    text.append(" ");
                }
                text.append(childText);
            }
            child = child.getNext();
        }
        return text.toString().trim();
    }

    private String extractNodeContent(Node node) {
        if (node == null) {
            return null;
        }
        if (node instanceof TableBlock) {
            return extractTableMarkdown(node);
        }
        return extractPlainText(node);
    }

    private String extractTableMarkdown(Node tableNode) {
        if (originalMarkdownContent == null) {
            return extractPlainText(tableNode);
        }
        try {
            BasedSequence chars = tableNode.getChars();
            if (chars != null && chars.length() > 0) {
                int startOffset = chars.getStartOffset();
                int endOffset = chars.getEndOffset();
                if (startOffset >= 0 && endOffset <= originalMarkdownContent.length() && startOffset < endOffset) {
                    return originalMarkdownContent.substring(startOffset, endOffset).trim();
                }
            }
            return extractPlainText(tableNode);
        } catch (Exception e) {
            return extractPlainText(tableNode);
        }
    }

    private String extractPlainText(Node node) {
        if (node == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        extractTextRecursive(node, text);
        return text.length() > 0 ? text.toString().trim() : null;
    }

    private void extractTextRecursive(Node node, StringBuilder text) {
        if (node == null || node instanceof Heading) {
            return;
        }
        Node child = node.getFirstChild();
        if (child != null) {
            boolean isFirstChild = true;
            while (child != null) {
                if (!isFirstChild && text.length() > 0) {
                    if (child instanceof Block) {
                        if (!text.toString().endsWith("\n")) {
                            text.append("\n");
                        }
                    } else {
                        text.append(" ");
                    }
                }
                extractTextRecursive(child, text);
                child = child.getNext();
                isFirstChild = false;
            }
            return;
        }
        BasedSequence chars = node.getChars();
        if (chars != null && chars.length() > 0) {
            String nodeText = chars.toString().trim();
            if (!nodeText.isEmpty()) {
                if (text.length() > 0 && !text.toString().endsWith("\n")) {
                    text.append(" ");
                }
                text.append(nodeText);
            }
        }
    }

    private record Section(String title, int headingLevel, List<String> headingPath, String content) {
    }

    private record TextSlice(String text, int start, int end) {
    }
}
