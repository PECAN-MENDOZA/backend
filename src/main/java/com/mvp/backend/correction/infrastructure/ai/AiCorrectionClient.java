package com.mvp.backend.correction.infrastructure.ai;

public interface AiCorrectionClient {

    AiCorrectionResponse correct(String originalText, String additionalContext);
}
