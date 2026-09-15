package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
     * Tamano de muestra. La cohorte base son los participantes con un "par completo": al menos una ejecucion
     * COMPLETED no excluida en cada condicion (sin filtro por palabras). Los participantes forman una particion:
     * {@code participantsTotal == participantsIncluded + participantsWithIncompletePair
     * + participantsWithoutEligibleRun} (sin ejecucion completada, o solo excluidas). Las ejecuciones tambien:
     * {@code runsCompleted == runsIncluded + runsExcluded + runsInIncompletePairs}.
     * {@code runsWithoutCountableWords} cuenta, entre las incluidas, las que no tienen palabras contables: PPM
     * las usa con valor 0 y PEO las omite (ver {@link PeoResult#participantsWithoutCountableWords}).
     */
    public record Sample(
            int participantsTotal,
            int participantsIncluded,
            int participantsWithIncompletePair,
            int participantsWithoutEligibleRun,
            int runsCompleted,
            int runsIncluded,
            int runsExcluded,
            int runsInIncompletePairs,
            int runsWithoutCountableWords) {
    }

    /**
     * PEO (criterios §3): comparacion emparejada sobre los participantes con palabras contables en ambas
     * condiciones ({@code participantsAnalyzed}; los demas incluidos se informan en
     * {@code participantsWithoutCountableWords}) y {@code runsAnalyzed} ejecuciones; reduccion relativa media
     * sobre los participantes con PEO sin asistencia > 0 ({@code relativeReductionSkipped} = participantes con
     * PEO sin asistencia 0) y {@code upperCiBelowZero}, el criterio del limite superior del IC 95 %; nunca una
     * declaracion de exito.
     */
    public record PeoResult(
            PairedSummary paired,
            int participantsAnalyzed,
            int participantsWithoutCountableWords,
            int runsAnalyzed,
            Double relativeReductionMean,
            int relativeReductionN,
            int relativeReductionSkipped,
            Boolean upperCiBelowZero) {
    }

    /**
     * PPM (criterios §4) sobre toda la cohorte incluida ({@code participantsAnalyzed}, {@code runsAnalyzed}; una
     * ejecucion sin palabras contables vale 0). {@code descriptive} es true mientras no exista un margen δPPM
     * configurado (y valido); entonces {@code nonInferior} es {@code null}. Con margen, no inferioridad =
     * limite inferior del IC 95 % de ΔPPM mayor que −δPPM.
     */
    public record PpmResult(
            PairedSummary paired,
            int participantsAnalyzed,
            int runsAnalyzed,
            boolean descriptive,
            Double nonInferiorityMargin,
            Boolean nonInferior) {
    }

    /**
     * TAS o TAS aceptada (criterios §5). {@code pooledRate} usa el denominador explicito agregado
     * (sugerencias evaluadas / aceptadas, sobre {@code runsAnalyzed} ejecuciones ASSISTED con al menos una) y
     * {@code pooledCi95Lower/Upper} es su intervalo de Wilson (z = 1.959964), en porcentaje: es el intervalo
     * inferencial. {@code participantMean}, {@code participantSd} y {@code participantCi95Lower/Upper} (t,
     * acotado a [0, 100]) describen el valor por participante, excluyendo a quienes no tienen denominador
     * ({@code participantsWithoutDenominator}); son puramente descriptivos. {@code descriptive} es true sin
     * limite configurado (y valido); con limite, {@code upperCiBelowLimit} compara el limite superior del
     * intervalo de Wilson agregado con el limite, nunca el promedio.
     */
    public record TasResult(
            int participantsEvaluated,
            int participantsWithoutDenominator,
            int runsAnalyzed,
            long suggestionsEvaluated,
            long harmfulSuggestions,
            Double pooledRate,
            Double pooledCi95Lower,
            Double pooledCi95Upper,
            Double participantMean,
            Double participantSd,
            Double participantCi95Lower,
            Double participantCi95Upper,
            boolean descriptive,
            Double limit,
            Boolean upperCiBelowLimit) {
    }

    /**
     * Estado de completitud de la anotacion de un tipo: {@code ADJUDICATED} (lote con adjudicacion vigente
     * que cubre todas las ejecuciones incluidas), {@code NO_BATCH}, {@code NOT_ADJUDICATED} (el lote mas
     * reciente del tipo, identificado en {@code batchId}, no tiene adjudicacion vigente; importar sus
     * evaluadores/adjudicacion en lugar de crear otro lote), {@code INCOMPLETE_COVERAGE} (la adjudicacion
     * vigente del lote mas reciente no cubre todas las ejecuciones/sugerencias incluidas), {@code NOT_APPLICABLE}
     * (nada que evaluar) o {@code NO_SAMPLE} (sin pares completos).
     */
    public record AnnotationStatus(
            String status,
            UUID batchId,
            String exportSha256,
            UUID adjudicationImportId,
            String message) {

        @JsonIgnore
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

    /**
     * Version de protocolo, modelo/backend/app observados en las ejecuciones incluidas y lotes usados.
     * {@code sessionsChangedAfterExport} (informativo; {@code null} sin lote semantico vigente) cuenta los
     * items semanticos cuya sesion viva ya no coincide con la aceptacion congelada en el lote: los resultados
     * siguen usando los valores congelados.
     */
    public record Provenance(
            List<Integer> protocolVersions,
            List<String> modelVersions,
            List<String> backendVersions,
            List<String> appVersions,
            List<Dataset> datasets,
            Double ppmNonInferiorityMargin,
            Double tasLimit,
            Integer sessionsChangedAfterExport,
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
