package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.ResearchAuditEvent;

public interface ResearchAuditEventRepository extends JpaRepository<ResearchAuditEvent, UUID> {

    List<ResearchAuditEvent> findByStudyIdOrderByCreatedAtDesc(UUID studyId);
}
