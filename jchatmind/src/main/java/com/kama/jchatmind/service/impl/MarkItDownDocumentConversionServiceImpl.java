package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.service.DocumentConversionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MarkItDownDocumentConversionServiceImpl implements DocumentConversionService {
    private static final Set<String> DIRECT_MARKDOWN_TYPES = Set.of("md", "markdown", "txt");

    @Value("${rag.document-converter.markitdown-command:markitdown}")
    private String markitdownCommand;

    @Value("${rag.document-converter.timeout-seconds:60}")
    private long timeoutSeconds;

    @Value("${rag.document-converter.max-file-size-mb:30}")
    private long maxFileSizeMb;

    @Value("${rag.document-converter.enabled-formats:md,markdown,txt,pdf,docx,pptx,xlsx,html,csv,json,xml}")
    private String enabledFormats;

    @Override
    public boolean supports(String fileType) {
        return enabledFormatSet().contains(normalizeFileType(fileType));
    }

    @Override
    public boolean requiresConversion(String fileType) {
        return !DIRECT_MARKDOWN_TYPES.contains(normalizeFileType(fileType));
    }

    @Override
    public String convertToMarkdown(Path sourcePath, String fileType, String originalFilename) {
        String normalizedFileType = normalizeFileType(fileType);
        validateSource(sourcePath, normalizedFileType, originalFilename);

        if (!requiresConversion(normalizedFileType)) {
            try {
                return Files.readString(sourcePath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new BizException("读取文档失败: " + e.getMessage());
            }
        }

        Path outputPath = null;
        try {
            outputPath = Files.createTempFile("markitdown-", ".md");
            ProcessBuilder processBuilder = new ProcessBuilder(
                    markitdownCommand,
                    sourcePath.toAbsolutePath().normalize().toString(),
                    "-o",
                    outputPath.toAbsolutePath().normalize().toString()
            );
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException("文档转换超时，请缩小文件后重试");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.warn("MarkItDown 转换失败: file={}, exitCode={}, output={}",
                        originalFilename, process.exitValue(), output);
                throw new BizException("文档转换失败，请确认 MarkItDown 已安装且文件格式受支持: " + output.trim());
            }

            String markdown = Files.readString(outputPath, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(markdown)) {
                throw new BizException("文档转换结果为空，可能是扫描件或图片内容暂不支持解析");
            }
            return markdown;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program")) {
                throw new BizException("文档转换器 MarkItDown 未安装或不可执行，请安装后重试");
            }
            throw new BizException("文档转换失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("文档转换被中断");
        } finally {
            if (outputPath != null) {
                try {
                    Files.deleteIfExists(outputPath);
                } catch (IOException e) {
                    log.debug("删除 MarkItDown 临时文件失败: {}", outputPath, e);
                }
            }
        }
    }

    private void validateSource(Path sourcePath, String fileType, String originalFilename) {
        if (!supports(fileType)) {
            throw new BizException("不支持的文件类型: " + fileType);
        }
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new BizException("待转换文件不存在: " + originalFilename);
        }
        try {
            long maxBytes = maxFileSizeMb * 1024 * 1024;
            if (Files.size(sourcePath) > maxBytes) {
                throw new BizException("文件超过大小限制: " + maxFileSizeMb + "MB");
            }
        } catch (IOException e) {
            throw new BizException("读取文件大小失败: " + e.getMessage());
        }
    }

    private Set<String> enabledFormatSet() {
        return Arrays.stream(enabledFormats.split(","))
                .map(this::normalizeFileType)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private String normalizeFileType(String fileType) {
        if (fileType == null) {
            return "";
        }
        return fileType.trim().toLowerCase(Locale.ROOT).replace(".", "");
    }
}
