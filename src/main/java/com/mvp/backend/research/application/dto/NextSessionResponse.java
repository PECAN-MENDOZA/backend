package com.mvp.backend.research.application.dto;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.research.domain.model.TaskVariant;

/** Unica asignacion permitida para la siguiente sesion del participante (la decide el servidor). */
public record NextSessionResponse(TaskVariant task, ExperimentCondition condition) {
}
