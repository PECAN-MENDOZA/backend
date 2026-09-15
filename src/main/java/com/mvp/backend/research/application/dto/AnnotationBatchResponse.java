package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mvp.backend.research.domain.model.AnnotationBatch;
import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationKind;
import com.mvp.backend.research.domain.model.AnnotationSlot;

/**
 * Resumen de un lote ciego. Nunca expone la correspondencia muestra -> participante/condicion ni el
 * contenido de las filas: solo tipo, tamano, hash, ranuras completas, historial de importaciones y acuerdo.
 * {@code adjudicationCurrent} es true solo cuando existe una importacion ADJUDICATED vigente resuelta
 * sobre las dos importaciones de evaluador vigentes; las metricas finales deben exigirlo.
 */
public record AnnotationBatchResponse(
        UUID id,
        AnnotationKind kind,
        List<String> columns,
        int rowCount,
        String exportSha256,
        Instant createdAt,
        List<AnnotationSlot> completedSlots,
        List<AnnotationImportResponse> imports,
        AgreementSummary agreement,
        boolean adjudicationCurrent) {

    public record AnnotationImportResponse(
            UUID id,
            AnnotationSlot slot,
            int version,
            String rater,
            String fileSha256,
            Instant importedAt,
            Instant supersededAt,
            boolean current) {

        public static AnnotationImportResponse from(AnnotationImport imported) {
            return new AnnotationImportResponse(
                    imported.getId(),
                    imported.getSlot(),
                    imported.getVersion(),
                    imported.getRater(),
                    imported.getFileSha256(),
                    imported.getImportedAt(),
                    imported.getSupersededAt(),
                    imported.isCurrent());
        }
    }

    public static AnnotationBatchResponse from(
            AnnotationBatch batch,
            List<AnnotationSlot> completedSlots,
            List<AnnotationImport> imports,
            AgreementSummary agreement,
            boolean adjudicationCurrent) {
        return new AnnotationBatchResponse(
                batch.getId(),
                batch.getKind(),
                batch.getKind().columns(),
                batch.getRowCount(),
                batch.getExportSha256(),
                batch.getCreatedAt(),
                completedSlots,
                imports.stream().map(AnnotationImportResponse::from).toList(),
                agreement,
                adjudicationCurrent);
    }
}
