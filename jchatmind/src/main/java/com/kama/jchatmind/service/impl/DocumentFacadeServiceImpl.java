package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentConversionService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.model.rag.MarkdownChunk;
import com.kama.jchatmind.service.MarkdownChunkingService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final DocumentConversionService documentConversionService;
    private final MarkdownChunkingService markdownChunkingService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObjectMapper objectMapper;
    private final Executor documentExecutor;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();
            if (!documentConversionService.supports(filetype)) {
                throw new BizException("不支持的文件类型: " + filetype);
            }

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            metadata.setOriginalFileType(filetype);
            metadata.setConversionStatus("pending");
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            documentExecutor.execute(() -> processUploadedDocument(
                    kbId, documentId, originalFilename, filetype, filePath, documentDTO));

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
            if (documentDTO.getMetadata() != null
                    && documentDTO.getMetadata().getMarkdownPath() != null
                    && !documentDTO.getMetadata().getMarkdownPath().equals(documentDTO.getMetadata().getFilePath())) {
                String markdownPath = documentDTO.getMetadata().getMarkdownPath();
                documentStorageService.deleteFile(markdownPath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
    }

    /**
     * 将上传文档转换为 Markdown，并解析生成 chunks。
     */
    private void processUploadedDocument(
            String kbId,
            String documentId,
            String originalFilename,
            String fileType,
            String filePath,
            DocumentDTO documentDTO
    ) {
        try {
            log.info("开始处理上传文档: kbId={}, documentId={}, filePath={}, fileType={}",
                    kbId, documentId, filePath, fileType);

            Path path = documentStorageService.getFilePath(filePath);
            String markdown = documentConversionService.convertToMarkdown(path, fileType, originalFilename);
            markdown = ensureMarkdownHasTitle(markdown, originalFilename);

            String markdownPath = filePath;
            if (documentConversionService.requiresConversion(fileType)) {
                markdownPath = documentStorageService.saveTextFile(
                        kbId,
                        documentId,
                        buildConvertedMarkdownFilename(originalFilename),
                        markdown
                );
            }

            DocumentDTO.MetaData metadata = documentDTO.getMetadata();
            metadata.setMarkdownPath(markdownPath);
            metadata.setConversionTool(documentConversionService.requiresConversion(fileType) ? "markitdown-cli" : "direct");
            metadata.setConversionStatus("success");
            metadata.setConversionError(null);
            updateDocumentMetadata(documentDTO);

            processMarkdownContent(kbId, documentId, markdown, originalFilename, fileType);
        } catch (Exception e) {
            log.error("处理上传文档失败: documentId={}", documentId, e);
            DocumentDTO.MetaData metadata = documentDTO.getMetadata();
            metadata.setConversionStatus("failed");
            metadata.setConversionError(e.getMessage());
            updateDocumentMetadata(documentDTO);
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException("处理文档失败: " + e.getMessage());
        }
    }

    /**
     * 处理 Markdown 内容，解析并生成 chunks。
     */
    private void processMarkdownContent(
            String kbId,
            String documentId,
            String markdown,
            String originalFilename,
            String sourceFileType
    ) {
        List<MarkdownChunk> markdownChunks = markdownChunkingService.chunk(
                markdown,
                new MarkdownChunkingService.ChunkingContext(originalFilename, sourceFileType, originalFilename)
        );

        if (markdownChunks.isEmpty()) {
            log.warn("Markdown 文档解析后没有生成任何 chunk: documentId={}", documentId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int chunkCount = 0;

        for (MarkdownChunk markdownChunk : markdownChunks) {
            float[] embedding = ragService.embed(markdownChunk.getEmbeddingText());

            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(kbId)
                    .docId(documentId)
                    .content(markdownChunk.getContent() != null ? markdownChunk.getContent() : "")
                    .metadata(buildChunkMetadata(originalFilename, sourceFileType, markdownChunk))
                    .embedding(embedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            int result = chunkBgeM3Mapper.insert(chunk);

            if (result > 0) {
                chunkCount++;
                log.debug("创建 chunk 成功: title={}, chunkId={}", markdownChunk.getSectionTitle(), chunk.getId());
            } else {
                log.warn("创建 chunk 失败: title={}", markdownChunk.getSectionTitle());
            }
        }
        log.info("Markdown 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
    }

    private String buildChunkMetadata(
            String originalFilename,
            String sourceFileType,
            MarkdownChunk markdownChunk
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentTitle", originalFilename);
        metadata.put("sectionTitle", markdownChunk.getSectionTitle());
        metadata.put("headingPath", markdownChunk.getHeadingPath());
        metadata.put("headingLevel", markdownChunk.getHeadingLevel());
        metadata.put("sourceFileType", sourceFileType);
        metadata.put("originalFileName", originalFilename);
        metadata.put("chunkIndex", markdownChunk.getChunkIndex());
        metadata.put("sectionChunkIndex", markdownChunk.getSectionChunkIndex());
        metadata.put("charStart", markdownChunk.getCharStart());
        metadata.put("charEnd", markdownChunk.getCharEnd());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new BizException("构建 chunk metadata 失败: " + e.getMessage());
        }
    }

    private String ensureMarkdownHasTitle(String markdown, String originalFilename) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        boolean hasHeading = markdown.lines().anyMatch(line -> line.trim().startsWith("#"));
        if (hasHeading) {
            return markdown;
        }
        String title = StringUtils.hasText(originalFilename) ? originalFilename : "未命名文档";
        return "# " + title + "\n\n" + markdown;
    }

    private String buildConvertedMarkdownFilename(String originalFilename) {
        String baseName = StringUtils.hasText(originalFilename) ? originalFilename : "converted";
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        return baseName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".converted.md";
    }

    private void updateDocumentMetadata(DocumentDTO documentDTO) {
        try {
            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentDTO.getId());
            updatedDocument.setCreatedAt(documentDTO.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(updatedDocument);
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档转换状态失败: " + e.getMessage());
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
