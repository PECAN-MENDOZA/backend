package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.StudyParticipant;

public interface StudyParticipantRepository extends JpaRepository<StudyParticipant, UUID> {

    List<StudyParticipant> findByStudyIdOrderByPseudonymAsc(UUID studyId);

    Optional<StudyParticipant> findByIdAndStudyId(UUID participantId, UUID studyId);

    Optional<StudyParticipant> findByStudyIdAndStudentId(UUID studyId, UUID studentId);
}
