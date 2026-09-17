package com.mvp.backend.sentencetest.application.dto;

import java.util.UUID;

public record StartSentenceResponse(UUID responseId, int position, String assistance, boolean alreadyStarted) {
}
