package com.mvp.backend.research.domain.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "protocol_tasks",
        uniqueConstraints = @UniqueConstraint(name = "uk_protocol_variant", columnNames = {"protocol_id", "variant"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProtocolTask {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "protocol_id", nullable = false, updatable = false)
    private StudyProtocol protocol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TaskVariant variant;

    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    private String promptText;

    ProtocolTask(StudyProtocol protocol, TaskVariant variant, String promptText) {
        if (promptText == null || promptText.isBlank()) {
            throw new IllegalArgumentException("Task prompt must not be blank");
        }
        this.id = UUID.randomUUID();
        this.protocol = protocol;
        this.variant = variant;
        this.promptText = promptText;
    }
}
