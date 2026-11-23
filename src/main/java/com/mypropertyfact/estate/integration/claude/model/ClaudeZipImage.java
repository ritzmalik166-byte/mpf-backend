package com.mypropertyfact.estate.integration.claude.model;

import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;

/** A validated image entry inside the uploaded ZIP. Bytes are re-read from the ZIP at import time. */
public record ClaudeZipImage(
        String entryName,
        String folder,
        String fileName,
        String projectKey,
        String typeToken,
        ImageKind kind,
        String format,
        int width,
        int height,
        long size) {
}
