package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mvp.backend.sentencetest.application.service.ResearchTestService.Cohort;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;

class TestExportCsvTests {

    private static final String HEADER = "test_code,student_username,attempt_id,position,kind,assistance,reference_text,"
            + "final_text,skipped,word_count,error_count,error_source,duration_first_key_ms,duration_start_ms,"
            + "suggestions_offered,suggestions_accepted,suggestions_rejected,suggestions_undone,model_version,"
            + "app_version,excluded";

    @Test
    void headerEncodingAndLineEndings() {
        byte[] bytes = TestExportCsv.build(SyntheticCohort.build(true));

        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).startsWith(HEADER + "\r\n");
        assertThat(text).endsWith("\r\n");
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");
        assertThat(HEADER.split(",")).hasSize(21);
    }

    @Test
    void oneRowPerResponseOfEveryCompletedAttemptIncludingExcluded() {
        Cohort cohort = SyntheticCohort.build(true);
        List<String> lines = lines(TestExportCsv.build(cohort));

        assertThat(lines).hasSize(1 + 16);
        TestAttempt excluded = cohort.completedAll().get(3);
        List<String> excludedRows = lines.stream().filter(l -> l.contains(excluded.getId().toString())).toList();
        assertThat(excludedRows).hasSize(4).allSatisfy(row -> assertThat(row).endsWith(",true"));
        assertThat(lines.stream().filter(l -> l.endsWith(",false")).count()).isEqualTo(12);
        assertThat(lines.get(1)).startsWith("PRUEBA-01,alumno-a," + cohort.completedAll().get(0).getId() + ",1,DICTATED,ASSISTED,El perro corre.,El pero corre.,false,3,1,AUTO,3000,3100,2,1,0,0,beto-v3,app-1,false");
    }

    @Test
    void quotesTextsWithCommasOrQuotes() {
        List<String> lines = lines(TestExportCsv.build(SyntheticCohort.build(false)));

        // Oracion 3 del alumno C: texto con coma y comillas -> entre comillas y comillas dobladas.
        String row = lines.stream().filter(l -> l.contains("alumno-c,") && l.contains(",3,FREE,")).findFirst().orElseThrow();
        assertThat(row).contains(",\"Un texto, con \"\"comillas\"\"\",false,4,,PENDING,5000,5000,1,1,0,0,beto-v4,app-2,false");
    }

    @Test
    void errorSourceAndBlanksFollowTheResponseState() {
        List<String> lines = lines(TestExportCsv.build(SyntheticCohort.build(true)));

        String annotated = lines.stream().filter(l -> l.contains("alumno-a,") && l.contains(",3,FREE,")).findFirst().orElseThrow();
        assertThat(annotated).contains(",Mi gato duerme mucho,false,4,1,ANNOTATED,6000,6500,3,2,0,0,");
        String skipped = lines.stream().filter(l -> l.contains("alumno-b,") && l.contains(",4,FREE,")).findFirst().orElseThrow();
        // Omitida: texto vacio, sin duracion desde primera tecla, sin error conocido.
        assertThat(skipped).contains(",Escribe sobre tu comida,,true,0,,PENDING,,500,0,0,0,0,beto-v3,app-1,false");
        String noModel = lines.stream().filter(l -> l.contains("alumno-d,") && l.contains(",1,DICTATED,")).findFirst().orElseThrow();
        assertThat(noModel).endsWith(",0,0,0,0,,app-1,true");
    }

    @Test
    void sha256IsStableForTheSameCohort() {
        Cohort cohort = SyntheticCohort.build(true);
        byte[] first = TestExportCsv.build(cohort);
        byte[] second = TestExportCsv.build(cohort);

        assertThat(second).isEqualTo(first);
        assertThat(TestExportCsv.sha256Hex(first)).isEqualTo(TestExportCsv.sha256Hex(second)).matches("[0-9a-f]{64}");
        assertThat(TestExportCsv.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void fileNameUsesTheTestCode() {
        assertThat(TestExportCsv.fileName("PRUEBA-01")).isEqualTo("test-PRUEBA-01-responses.csv");
    }

    @Test
    void usernamesComeFromTheCohortMap() {
        Cohort cohort = SyntheticCohort.build(false);
        UUID studentA = cohort.completedAll().get(0).getStudent().getId();
        assertThat(cohort.usernameByStudent().get(studentA)).isEqualTo("alumno-a");
    }

    private static List<String> lines(byte[] bytes) {
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        return Arrays.asList(text.split("\r\n"));
    }
}
