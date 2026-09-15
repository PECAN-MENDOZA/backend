package com.mvp.backend.research.application.dto;

/** CSV exportado (UTF-8, sin BOM) listo para descargar; {@code sha256} es el hash de exactamente estos bytes. */
public record AnnotationCsvFile(String filename, byte[] bytes, String sha256) {
}
