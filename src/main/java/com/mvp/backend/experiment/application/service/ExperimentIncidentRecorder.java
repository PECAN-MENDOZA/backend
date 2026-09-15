package com.mvp.backend.experiment.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;

/**
 * Registra una incidencia sobre una ejecucion en una transaccion propia, de modo que sobreviva
 * al rollback de la operacion que fallo (p. ej. una correccion cuya llamada a la IA no respondio).
 * El motivo es un codigo fijo ({@code AI_REQUEST_FAILED}); nunca se guarda el texto del alumno.
 */
@Service
public class ExperimentIncidentRecorder {

    private final ExperimentRunRepository runRepository;

    public ExperimentIncidentRecorder(ExperimentRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID runId, String reason) {
        runRepository.findById(runId).ifPresent(run -> run.recordIncident(reason));
    }
}
