package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.research.domain.model.ProtocolStatus;
import com.mvp.backend.research.domain.model.ProtocolTask;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.TaskVariant;

public record StudyProtocolResponse(
        UUID id,
        UUID studyId,
        int version,
        ProtocolStatus status,
        String taskAPrompt,
        String taskBPrompt,
        Instant createdAt) {

    public static StudyProtocolResponse from(StudyProtocol protocol) {
        return new StudyProtocolResponse(
                protocol.getId(),
                protocol.getStudy().getId(),
                protocol.getVersion(),
                protocol.getStatus(),
                protocol.findTask(TaskVariant.TASK_A).map(ProtocolTask::getPromptText).orElse(null),
                protocol.findTask(TaskVariant.TASK_B).map(ProtocolTask::getPromptText).orElse(null),
                protocol.getCreatedAt());
    }
}
