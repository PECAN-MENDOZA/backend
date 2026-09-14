package com.mvp.backend.experiment.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;

public interface ExperimentRunRepository extends JpaRepository<ExperimentRun, UUID> {

    Optional<ExperimentRun> findByIdAndParticipantStudentId(UUID runId, UUID studentId);

    Optional<ExperimentRun> findFirstByAccessCodeHashAndStatus(String hash, ExperimentRunStatus status);

    Optional<ExperimentRun> findFirstByParticipantStudentIdAndStatus(UUID studentId, ExperimentRunStatus status);

    // Restauracion: ACTIVE, o PENDING ya canjeada (la app se cerro antes de confirmar el inicio).
    @Query("""
            select r from ExperimentRun r
            where r.participant.student.id = :studentId
              and (r.status = 'ACTIVE' or (r.status = 'PENDING' and r.redeemedAt is not null))
            order by r.redeemedAt desc
            """)
    List<ExperimentRun> findRestorableByStudentId(@Param("studentId") UUID studentId);

    long countByParticipantIdAndStatus(UUID participantId, ExperimentRunStatus status);

    List<ExperimentRun> findByParticipantIdOrderByCreatedAtAsc(UUID participantId);

    List<ExperimentRun> findByParticipantStudyIdOrderByCreatedAtAsc(UUID studyId);
}
