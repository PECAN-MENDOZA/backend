package com.mvp.backend.sentencetest.application.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Terminar una oracion. Los offsets son milisegundos desde Comenzar segun el reloj monotonico del telefono. */
public record FinishSentenceRequest(
        @Size(max = 5000) String finalText,
        @Min(0) Long firstKeyOffsetMs,
        @NotNull @Min(0) Long finishedOffsetMs,
        @Min(0) int suggestionsOffered,
        @Min(0) int suggestionsAccepted,
        @Min(0) int suggestionsRejected,
        @Min(0) int suggestionsUndone,
        boolean skipped,
        @NotNull UUID completionKey) {
}
