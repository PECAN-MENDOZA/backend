package com.mvp.backend.sentencetest.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.mvp.backend.sentencetest.application.service.ResearchTestService.Cohort;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;

/**
 * CSV de exportacion: UTF-8 con BOM, CRLF, comillas RFC 4180 y una fila por respuesta de cada intento
 * COMPLETED (los excluidos con excluded=true; los cancelados no aparecen). Sin nombres reales.
 */
public final class TestExportCsv {

    public static final List<String> COLUMNS = List.of(
            "test_code", "student_username", "attempt_id", "position", "kind", "assistance", "reference_text",
            "final_text", "skipped", "word_count", "error_count", "error_source", "duration_first_key_ms",
            "duration_start_ms", "suggestions_offered", "suggestions_accepted", "suggestions_rejected",
            "suggestions_undone", "model_version", "app_version", "excluded");

    private static final String BOM = "\uFEFF";
    private static final String CRLF = "\r\n";

    private TestExportCsv() {
    }

    public static byte[] build(Cohort cohort) {
        StringBuilder out = new StringBuilder(BOM);
        out.append(String.join(",", COLUMNS)).append(CRLF);
        String code = cohort.test().getCode();
        for (TestAttempt attempt : cohort.completedAll()) {
            String username = cohort.usernameByStudent().get(attempt.getStudent().getId());
            for (TestResponse response : cohort.responsesByAttempt().getOrDefault(attempt.getId(), List.of())) {
                appendRow(out, code, username, attempt, response);
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String fileName(String code) {
        return "test-" + code + "-responses.csv";
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Palabras contables: la referencia en dictadas, el texto final en libres (igual que el panel). */
    static int wordCount(TestResponse response) {
        TestSentence sentence = response.getSentence();
        String text = sentence.getKind() == SentenceKind.DICTATED ? sentence.getReferenceText() : response.getFinalText();
        return SentenceAligner.tokenize(text).size();
    }

    /** AUTO en dictadas, ANNOTATED en libres anotadas y PENDING en libres sin anotar. */
    static String errorSource(TestResponse response) {
        if (response.getSentence().getKind() == SentenceKind.DICTATED) {
            return "AUTO";
        }
        return response.getAnnotatedErrorCount() != null ? "ANNOTATED" : "PENDING";
    }

    private static void appendRow(StringBuilder out, String code, String username, TestAttempt attempt,
            TestResponse response) {
        TestSentence sentence = response.getSentence();
        Object[] values = {
                code,
                username,
                attempt.getId(),
                response.getPosition(),
                sentence.getKind().name(),
                sentence.getAssistance().name(),
                sentence.getReferenceText(),
                response.getFinalText(),
                response.isSkipped(),
                wordCount(response),
                response.effectiveErrorCount(),
                errorSource(response),
                response.getDurationFromFirstKeyMs(),
                response.getDurationFromStartMs(),
                response.getSuggestionsOffered(),
                response.getSuggestionsAccepted(),
                response.getSuggestionsRejected(),
                response.getSuggestionsUndone(),
                attempt.getModelVersion(),
                attempt.getAppVersion(),
                attempt.isExcluded()};
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(values[i]));
        }
        out.append(CRLF);
    }

    /** Vacio para null; comillas (dobladas dentro) si el valor contiene coma, comillas o saltos de linea. */
    private static String quote(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
