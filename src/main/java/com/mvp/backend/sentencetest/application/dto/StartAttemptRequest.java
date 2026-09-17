package com.mvp.backend.sentencetest.application.dto;

import jakarta.validation.constraints.Size;

public record StartAttemptRequest(@Size(max = 80) String appVersion) {
}
