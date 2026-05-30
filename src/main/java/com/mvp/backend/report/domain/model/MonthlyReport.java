package com.mvp.backend.report.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.mvp.backend.student.domain.model.Student;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "monthly_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReport {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "\"month\"", nullable = false)
    private LocalDate month;

    @Column(name = "total_submissions", nullable = false)
    private int totalSubmissions;

    @Column(name = "total_accepted", nullable = false)
    private int totalAccepted;

    @Column(name = "acceptance_rate", nullable = false)
    private double acceptanceRate;

    @Column(name = "frequent_errors_json", columnDefinition = "TEXT")
    private String frequentErrorsJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
