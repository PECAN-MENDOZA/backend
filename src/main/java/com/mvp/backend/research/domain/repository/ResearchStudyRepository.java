package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.research.domain.model.ResearchStudy;

import jakarta.persistence.LockModeType;

public interface ResearchStudyRepository extends JpaRepository<ResearchStudy, UUID> {

    Optional<ResearchStudy> findByIdAndCreatedById(UUID studyId, UUID researcherId);

    List<ResearchStudy> findByCreatedByIdOrderByCreatedAtDesc(UUID researcherId);

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ResearchStudy s where s.id = :id and s.createdBy.id = :researcherId")
    Optional<ResearchStudy> findOwnedForUpdate(@Param("id") UUID id, @Param("researcherId") UUID researcherId);
}
