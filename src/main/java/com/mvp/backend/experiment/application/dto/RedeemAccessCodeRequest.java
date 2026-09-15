package com.mvp.backend.experiment.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.mvp.backend.experiment.domain.model.AccessCode;

public record RedeemAccessCodeRequest(@NotBlank @Pattern(regexp = AccessCode.PATTERN) String code) {
}
