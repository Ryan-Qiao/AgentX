package com.kama.jchatmind.service;

import java.nio.file.Path;

public interface DocumentConversionService {
    boolean supports(String fileType);

    boolean requiresConversion(String fileType);

    String convertToMarkdown(Path sourcePath, String fileType, String originalFilename);
}
