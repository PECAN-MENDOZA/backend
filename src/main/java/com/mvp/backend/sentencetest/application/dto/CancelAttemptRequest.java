package com.mvp.backend.sentencetest.application.dto;

import com.mvp.backend.sentencetest.domain.model.AttemptCancelReason;

import jakarta.validation.constraints.NotNull;

public record CancelAttemptRequest(@NotNull AttemptCancelReason reason) {
}
