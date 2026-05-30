package com.mvp.backend.kpi.application.dto;

import com.mvp.backend.correction.domain.model.ErrorType;

public record ErrorDistributionItem(ErrorType type, long count, double percentage) {
}
