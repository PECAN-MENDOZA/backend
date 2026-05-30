package com.mvp.backend.report.application.dto;

public record ReportPdfDownload(String filename, byte[] content) {
}
