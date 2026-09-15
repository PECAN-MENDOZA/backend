package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.StudyStatus;

public record ResearchStudyResponse(
        UUID id,
        String code,
        String title,
        StudyStatus status,
        Integer activeProtocolVersion,
        Instant createdAt) {

    public static ResearchStudyResponse from(ResearchStudy study, Integer activeProtocolVersion) {
        return new ResearchStudyResponse(
                study.getId(),
                study.getCode(),
                study.getTitle(),
                study.getStatus(),
                activeProtocolVersion,
                study.getCreatedAt());
    }
}
