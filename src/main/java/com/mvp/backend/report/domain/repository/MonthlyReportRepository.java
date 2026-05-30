package com.mvp.backend.report.domain.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.report.domain.model.MonthlyReport;

public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, UUID> {

    Optional<MonthlyReport> findByStudentIdAndMonth(UUID studentId, LocalDate month);
}
