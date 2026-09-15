package com.mvp.backend.experiment.application.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;

/**
 * Registra una incidencia sobre una ejecucion en una transaccion propia, de modo que sobreviva
 * al rollback de la operacion que fallo (p. ej. una correccion cuya llamada a la IA no respondio).
 * El motivo es un codigo fijo ({@code AI_REQUEST_FAILED}); nunca se guarda el texto del alumno.
 *
 * <p>La lectura toma el bloqueo de fila: dos fallos simultaneos se serializan y ambos cuentan.
 * No debe invocarse mientras el llamador ya tenga bloqueada la misma ejecucion (se bloquearia a
 * si mismo): dentro de esa transaccion se registra la incidencia directamente sobre la entidad.
 */
@Service
public class ExperimentIncidentRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExperimentIncidentRecorder.class);

    private final ExperimentRunRepository runRepository;

    public ExperimentIncidentRecorder(ExperimentRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID runId, String reason) {
        runRepository.findByIdForUpdate(runId).ifPresentOrElse(
                run -> run.recordIncident(reason),
                () -> log.warn("Incident for unknown experiment run {}", runId));
    }
}
