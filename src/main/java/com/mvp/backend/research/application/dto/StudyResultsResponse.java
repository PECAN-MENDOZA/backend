package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mvp.backend.research.domain.model.AnnotationKind;

/**
 * Resultados de un estudio (spec §9.4) en su jerarquia: PEO (principal), PPM (complementario, no
 * inferioridad), TAS y TAS aceptada (seguridad semantica). F0.5 no forma parte de este objeto: vive en
 * {@link TechnicalEvaluationResponse} y en su propio endpoint.
 *
 * <p>Una metrica que depende de anotacion humana es {@code null} —nunca un numero parcial— mientras no
 * exista una adjudicacion vigente que cubra todas las ejecuciones incluidas; el bloque de anotacion
 * correspondiente explica por que. Solo aparecen seudonimos {@code P-nnn}.
 */
public record StudyResultsResponse(
        UUID studyId,
        String studyCode,
        String studyTitle,
        Sample sample,
        PeoResult peo,
        PpmResult ppm,
        TasResult tas,
        TasResult tasAccepted,
        AnnotationStatus orthographyAnnotation,
        AnnotationStatus semanticAnnotation,
        List<ParticipantResult> participants,
        Provenance provenance) {

    /**
     * Tamano de muestra. Se incluye un participante solo con un "par completo": al menos una ejecucion
     * COMPLETED no excluida y con palabras contables en cada condicion. Las exclusiones se informan
     * separadas: ejecuciones excluidas por el investigador, ejecuciones de pares incompletos y
     * ejecuciones sin palabras contables.
     */
    public record Sample(
            int participantsTotal,
            int participantsIncluded,
            int participantsWithIncompletePair,
            int runsCompleted,
            int runsIncluded,
            int runsExcluded,
            int runsInIncompletePairs,
            int runsWithoutCountableWords) {
    }

    /**
     * PEO (criterios §3): comparacion emparejada, reduccion relativa media sobre los participantes con
     * PEO sin asistencia > 0 ({@code relativeReductionSkipped} = participantes con PEO sin asistencia 0) y
     * {@code upperCiBelowZero}, el criterio del limite superior del IC 95 %; nunca una declaracion de exito.
     */
    public record PeoResult(
            PairedSummary paired,
            Double relativeReductionMean,
            int relativeReductionN,
            int relativeReductionSkipped,
            Boolean upperCiBelowZero) {
    }

    /**
     * PPM (criterios §4). {@code descriptive} es true mientras no exista un margen δPPM configurado;
     * entonces {@code nonInferior} es {@code null}. Con margen, no inferioridad = limite inferior del IC 95 %
     * de ΔPPM mayor que −δPPM.
     */
    public record PpmResult(
            PairedSummary paired,
            boolean descriptive,
            Double nonInferiorityMargin,
            Boolean nonInferior) {
    }

    /**
     * TAS o TAS aceptada (criterios §5). {@code pooledRate} usa el denominador explicito agregado
     * (sugerencias evaluadas / aceptadas); {@code participantMean} e IC 95 % (t) se calculan sobre el valor
     * por participante, excluyendo a quienes no tienen denominador ({@code participantsWithoutDenominator}).
     * {@code descriptive} es true sin limite configurado; con limite, {@code upperCiBelowLimit} compara el
     * limite superior del IC, no el promedio.
     */
    public record TasResult(
            int participantsEvaluated,
            int participantsWithoutDenominator,
            long suggestionsEvaluated,
            long harmfulSuggestions,
            Double pooledRate,
            Double participantMean,
            Double participantSd,
            Double ci95Lower,
            Double ci95Upper,
            boolean descriptive,
            Double limit,
            Boolean upperCiBelowLimit) {
    }

    /**
     * Estado de completitud de la anotacion de un tipo: {@code ADJUDICATED} (lote con adjudicacion vigente
     * que cubre todas las ejecuciones incluidas), {@code NO_BATCH}, {@code NOT_ADJUDICATED} (hay lotes pero
     * ninguno con adjudicacion vigente), {@code INCOMPLETE_COVERAGE} (la adjudicacion vigente no cubre
     * todas las ejecuciones/sugerencias incluidas), {@code NOT_APPLICABLE} (no hay sugerencias que evaluar)
     * o {@code NO_SAMPLE} (sin pares completos).
     */
    public record AnnotationStatus(
            String status,
            UUID batchId,
            String exportSha256,
            UUID adjudicationImportId,
            String message) {

        public boolean isAdjudicated() {
            return "ADJUDICATED".equals(status);
        }
    }

    /** Fila por participante incluido; todo valor que dependa de una anotacion no vigente es {@code null}. */
    public record ParticipantResult(
            String pseudonym,
            Double peoAssisted,
            Double peoUnassisted,
            Double peoDelta,
            Double peoRelativeReduction,
            Double ppmAssisted,
            Double ppmUnassisted,
            Double ppmDelta,
            Double tas,
            Double tasAccepted) {
    }

    /** Version de protocolo, modelo/backend/app observados en las ejecuciones incluidas y lotes usados. */
    public record Provenance(
            List<Integer> protocolVersions,
            List<String> modelVersions,
            List<String> backendVersions,
            List<String> appVersions,
            List<Dataset> datasets,
            Double ppmNonInferiorityMargin,
            Double tasLimit,
            Instant computedAt) {
    }

    public record Dataset(
            AnnotationKind kind,
            UUID batchId,
            String exportSha256,
            int rowCount,
            UUID adjudicationImportId,
            int adjudicationVersion,
            String adjudicationSha256) {
    }
}
