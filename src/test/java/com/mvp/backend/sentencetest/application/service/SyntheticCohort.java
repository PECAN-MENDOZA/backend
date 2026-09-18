package com.mvp.backend.sentencetest.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mvp.backend.sentencetest.application.service.ResearchTestService.Cohort;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.student.domain.model.Student;

/**
 * Cohorte sintetica en memoria: 3 alumnos x 4 oraciones (2 dictadas, 2 libres; 2 con ayuda, 2 sin) y,
 * opcionalmente, un cuarto alumno con intento completado pero excluido.
 *
 * <pre>
 * pos kind     assist   ref                 A                          B                        C
 * 1   DICTATED ASSISTED "El perro corre."   1 err, 3000 ms, 2/1        0 err, 2000 ms, 1/0      2 err, 6000 ms, 2/2
 * 2   DICTATED UNASSIST "La casa es azul."  2 err, 4000 ms             1 err, 2000 ms           0 err, 8000 ms
 * 3   FREE     ASSISTED                     4 palabras ann 1, 6000, 3/2 "Hola" ann 0, 1000, 0/0 4 palabras sin anotar, 5000, 1/1
 * 4   FREE     UNASSIST                     "Me gusta el pan" ann 0, 8000  omitida               "Me gusta" ann 1, 4000
 * </pre>
 * Errores/100 por alumno: A con 200/7, sin 25; B con 0, sin 25; C con 200/3, sin 100/6.
 * PPM: A con 7/0.15, sin 40; B con 80, sin 120; C con 7/(11000/60000), sin 30.
 */
final class SyntheticCohort {

    static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
    static final UUID RESEARCHER = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    static final String FREE_WITH_QUOTES = "Un texto, con \"comillas\"";

    private SyntheticCohort() {
    }

    static Cohort build(boolean withExcluded) {
        SentenceTest test = new SentenceTest("PRUEBA-01", "Prueba piloto", RESEARCHER);
        test.activate(4, NOW);
        List<TestSentence> sentences = List.of(
                new TestSentence(test, 1, SentenceKind.DICTATED, "El perro corre.", Assistance.ASSISTED),
                new TestSentence(test, 2, SentenceKind.DICTATED, "La casa es azul.", Assistance.UNASSISTED),
                new TestSentence(test, 3, SentenceKind.FREE, "Escribe sobre tu mascota", Assistance.ASSISTED),
                new TestSentence(test, 4, SentenceKind.FREE, "Escribe sobre tu comida", Assistance.UNASSISTED));

        Map<UUID, List<TestResponse>> responses = new LinkedHashMap<>();
        Map<UUID, String> usernames = new LinkedHashMap<>();
        List<TestAttempt> completed = new ArrayList<>();
        List<TestAttempt> all = new ArrayList<>();

        TestAttempt a = attempt(test, "alumno-a", "app-1", "backend-1", "beto-v3");
        responses.put(a.getId(), List.of(
                dictated(a, sentences.get(0), "El pero corre.", 1, 100L, 3100L, 2, 1),
                dictated(a, sentences.get(1), "La kasa es asul.", 2, 0L, 4000L, 0, 0),
                free(a, sentences.get(2), "Mi gato duerme mucho", 1, 500L, 6500L, 3, 2),
                free(a, sentences.get(3), "Me gusta el pan", 0, 0L, 8000L, 0, 0)));
        TestAttempt b = attempt(test, "alumno-b", "app-1", "backend-1", "beto-v3");
        responses.put(b.getId(), List.of(
                dictated(b, sentences.get(0), "El perro corre.", 0, 0L, 2000L, 1, 0),
                dictated(b, sentences.get(1), "La casa es azúl.", 1, 0L, 2000L, 0, 0),
                free(b, sentences.get(2), "Hola", 0, 0L, 1000L, 0, 0),
                skipped(b, sentences.get(3))));
        TestAttempt c = attempt(test, "alumno-c", "app-2", "backend-1", "beto-v4");
        responses.put(c.getId(), List.of(
                dictated(c, sentences.get(0), "El pero core.", 2, 0L, 6000L, 2, 2),
                dictated(c, sentences.get(1), "La casa es azul.", 0, 0L, 8000L, 0, 0),
                free(c, sentences.get(2), FREE_WITH_QUOTES, null, 0L, 5000L, 1, 1),
                free(c, sentences.get(3), "Me gusta", 1, 0L, 4000L, 0, 0)));
        for (TestAttempt attempt : List.of(a, b, c)) {
            attempt.complete(NOW);
            completed.add(attempt);
            all.add(attempt);
            usernames.put(attempt.getStudent().getId(), attempt.getStudent().getUsername());
        }
        if (withExcluded) {
            TestAttempt d = attempt(test, "alumno-d", "app-1", "backend-1", null);
            responses.put(d.getId(), List.of(
                    dictated(d, sentences.get(0), "El perro corre.", 0, 0L, 1000L, 0, 0),
                    dictated(d, sentences.get(1), "La casa es azul.", 0, 0L, 1000L, 0, 0),
                    free(d, sentences.get(2), "Tengo un perro", 0, 0L, 1000L, 0, 0),
                    free(d, sentences.get(3), "Me gusta la sopa", 0, 0L, 1000L, 0, 0)));
            d.complete(NOW);
            d.exclude("Interrumpido por el timbre del recreo", RESEARCHER, NOW);
            all.add(d);
            usernames.put(d.getStudent().getId(), d.getStudent().getUsername());
        }
        return new Cohort(test, sentences, completed, responses, usernames, all);
    }

    private static TestAttempt attempt(SentenceTest test, String username, String app, String backend, String model) {
        TestAttempt attempt = new TestAttempt(test, new Student(username, "Colegio", "hash"), app, backend, NOW);
        attempt.recordModelVersion(model);
        return attempt;
    }

    private static TestResponse dictated(TestAttempt attempt, TestSentence sentence, String text, int errors,
            long firstKey, long finished, int offered, int accepted) {
        TestResponse response = new TestResponse(attempt, sentence, NOW);
        response.finish(text, firstKey, finished, false, new TestResponse.Counters(offered, accepted, 0, 0),
                UUID.randomUUID(), NOW);
        response.recordAutoErrors(errors, "{\"error_count\":" + errors + "}");
        return response;
    }

    private static TestResponse free(TestAttempt attempt, TestSentence sentence, String text, Integer annotated,
            long firstKey, long finished, int offered, int accepted) {
        TestResponse response = new TestResponse(attempt, sentence, NOW);
        response.finish(text, firstKey, finished, false, new TestResponse.Counters(offered, accepted, 0, 0),
                UUID.randomUUID(), NOW);
        if (annotated != null) {
            response.annotate(annotated, RESEARCHER, NOW);
        }
        return response;
    }

    private static TestResponse skipped(TestAttempt attempt, TestSentence sentence) {
        TestResponse response = new TestResponse(attempt, sentence, NOW);
        response.finish("", null, 500L, true, TestResponse.Counters.ZERO, UUID.randomUUID(), NOW);
        return response;
    }
}
