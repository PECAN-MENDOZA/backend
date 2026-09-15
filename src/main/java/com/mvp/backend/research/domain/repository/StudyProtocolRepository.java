package com.mvp.backend.research.domain.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.ProtocolStatus;
import com.mvp.backend.research.domain.model.StudyProtocol;

public interface StudyProtocolRepository extends JpaRepository<StudyProtocol, UUID> {

    Optional<StudyProtocol> findByIdAndStudyId(UUID protocolId, UUID studyId);

    Optional<StudyProtocol> findFirstByStudyIdAndStatus(UUID studyId, ProtocolStatus status);

    Optional<StudyProtocol> findFirstByStudyIdOrderByVersionDesc(UUID studyId);
}
