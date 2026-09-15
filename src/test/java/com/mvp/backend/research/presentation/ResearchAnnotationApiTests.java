package com.mvp.backend.research.presentation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationSlot;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.TaskVariant;
import com.mvp.backend.research.domain.repository.AnnotationImportRepository;
import com.mvp.backend.research.domain.repository.AnnotationItemRepository;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.research.domain.repository.StudyProtocolRepository;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ResearchAnnotationApiTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ResearcherRepository researcherRepository;
    @Autowired
    private ResearchStudyRepository studyRepository;
    @Autowired
    private StudyProtocolRepository protocolRepository;
    @Autowired
    private StudyParticipantRepository participantRepository;
    @Autowired
    private ExperimentRunRepository runRepository;
    @Autowired
    private CorrectionSessionRepository sessionRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private AnnotationItemRepository itemRepository;
    @Autowired
    private AnnotationImportRepository importRepository;
    @Autowired
    private ResearchAuditEventRepository auditRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private Clock clock;

    private UUID researcherId;
    private UUID otherResearcherId;
    private ResearchStudy study;
    private StudyProtocol protocol;
    private Student student;
    private String base;

    @BeforeEach
    void setUp() {
        Researcher researcher = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash"));
        researcherId = researcher.getId();
        otherResearcherId = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash")).getId();
        student = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash"));
        study = new ResearchStudy("EXP-" + UUID.randomUUID().toString().substring(0, 8), "Anotacion", researcher);
        study.activate();
        study = studyRepository.save(study);
        protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Cuenta tu fin de semana");
        protocol.addTask(TaskVariant.TASK_B, "Describe tu escuela");
        protocol.activate();
        protocol = protocolRepository.save(protocol);
        base = "/api/v1/research/studies/" + study.getId() + "/annotation-batches";
    }

    @Test
    void teacherAndStudentAreForbiddenOnEveryAnnotationRoute() throws Exception {
        UUID batchId = UUID.randomUUID();
        for (String role : List.of("ROLE_TEACHER", "ROLE_STUDENT")) {
            mockMvc.perform(as(role, post(base).param("kind", "ORTHOGRAPHY"))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, get(base))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, get(base + "/" + batchId))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, get(base + "/" + batchId + "/export"))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, upload(batchId, "RATER_1", "Ana", "sample_code,score\n")))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get(base)).andExpect(status().isUnauthorized());
    }

    @Test
    void blindOrthographyRoundTripWithTwoRatersAndAdjudication() throws Exception {
        String assistedText = "Hola, mundo \"asistido\"";
        String unassistedText = "texto sin\nayuda";
        completedRun(1, ExperimentCondition.ASSISTED, assistedText, null);
        completedRun(2, ExperimentCondition.UNASSISTED, unassistedText, null);
        completedRun(3, ExperimentCondition.ASSISTED, "TEXTO EXCLUIDO", "Excluded for a documented reason");

        mockMvc.perform(asResearcher(researcherId, post(base).param("kind", "BOGUS"))).andExpect(status().isBadRequest());
        mockMvc.perform(asResearcher(otherResearcherId, post(base).param("kind", "ORTHOGRAPHY")))
                .andExpect(status().isNotFound());

        JsonNode batch = json(mockMvc.perform(asResearcher(researcherId, post(base).param("kind", "ORTHOGRAPHY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("ORTHOGRAPHY"))
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.completedSlots").isEmpty())
                .andExpect(jsonPath("$.agreement.complete").value(false))
                .andExpect(jsonPath("$.agreement.weightedKappa").doesNotExist()));
        UUID batchId = UUID.fromString(batch.get("id").asText());
        assertThat(batch.toString()).doesNotContain("P-00", "ASSISTED", assistedText, "T-");
        assertThat(batch.get("adjudicationCurrent").asBoolean()).isFalse();

        MvcResult download = mockMvc.perform(asResearcher(researcherId, get(base + "/" + batchId + "/export")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andReturn();
        String csv = new String(download.getResponse().getContentAsByteArray(), UTF_8);
        assertThat(csv).startsWith("sample_code,text,score\n")
                .contains("\"Hola, mundo \"\"asistido\"\"\",\n")
                .contains("\"texto sin\nayuda\",\n")
                .doesNotContain("P-001", "P-002", "ASSISTED", "UNASSISTED", "TEXTO EXCLUIDO", "alumno-");
        assertThat(download.getResponse().getHeader("X-Content-SHA256")).isEqualTo(batch.get("exportSha256").asText());
        mockMvc.perform(asResearcher(otherResearcherId, get(base + "/" + batchId + "/export")))
                .andExpect(status().isNotFound());

        List<String> codes = itemRepository.findByBatchIdOrderByPositionAsc(batchId).stream()
                .map(item -> item.getSampleCode()).toList();
        assertThat(codes).hasSize(2).allMatch(code -> code.matches("T-" + AccessCode.PATTERN));

        // Adjudication before the two raters is refused.
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "ADJUDICATED", "Consenso",
                        "sample_code,score\n" + codes.get(0) + ",1\n" + codes.get(1) + ",0\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Adjudication requires two complete rater imports"));

        // Rater 1 fills the score column of the exported file; rater 2 sends the minimal layout.
        String filledExport = csv.replace("\"\"\",\n", "\"\"\",2\n").replace("ayuda\",\n", "ayuda\",0\n");
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_1", "Ana", filledExport)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedSlots[0]").value("RATER_1"))
                .andExpect(jsonPath("$.imports.length()").value(1))
                .andExpect(jsonPath("$.imports[0].rater").value("Ana"))
                .andExpect(jsonPath("$.imports[0].version").value(1))
                .andExpect(jsonPath("$.agreement.complete").value(false));
        String minimal = "sample_code,score\n" + codes.get(0) + ",1\n" + codes.get(1) + ",0\n";
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_2", "ana", minimal)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("RATER_1 and RATER_2 must be distinct raters"));
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_2", "Beto", "sample_code,score\n" + codes.get(0) + ",1\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Missing scores for 1 sample(s)")));
        JsonNode summary = json(mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_2", "Beto", minimal)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedSlots.length()").value(2))
                .andExpect(jsonPath("$.agreement.complete").value(true))
                .andExpect(jsonPath("$.agreement.exactAgreement").isNumber())
                .andExpect(jsonPath("$.agreement.weightedKappa").isNumber()));
        assertThat(summary.toString()).doesNotContain(codes.get(0), codes.get(1), "Hola", "P-00");

        // Same file again for the same slot is a conflict; a changed file supersedes without deleting.
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_2", "Beto", minimal)))
                .andExpect(status().isConflict());
        String revised = "sample_code,score\n" + codes.get(0) + ",2\n" + codes.get(1) + ",0\n";
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_2", "Beto", revised)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imports.length()").value(3))
                .andExpect(jsonPath("$.imports[1].slot").value("RATER_2"))
                .andExpect(jsonPath("$.imports[1].current").value(false))
                .andExpect(jsonPath("$.imports[2].version").value(2))
                .andExpect(jsonPath("$.imports[2].current").value(true));
        assertThat(importRepository.findByBatchIdOrderByVersionAsc(batchId)).hasSize(3);

        mockMvc.perform(asResearcher(researcherId, upload(batchId, "ADJUDICATED", "Consenso", revised)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedSlots.length()").value(3))
                .andExpect(jsonPath("$.adjudicationCurrent").value(true));
        assertThat(itemRepository.findByBatchIdOrderByPositionAsc(batchId))
                .allMatch(item -> item.score(AnnotationSlot.ADJUDICATED) != null);

        // A rater revision after adjudication supersedes the adjudication and clears its scores.
        String rater1Revised = "sample_code,score\n" + codes.get(0) + ",0\n" + codes.get(1) + ",0\n";
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_1", "Ana", rater1Revised)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedSlots.length()").value(2))
                .andExpect(jsonPath("$.adjudicationCurrent").value(false))
                .andExpect(jsonPath("$.imports.length()").value(5))
                .andExpect(jsonPath("$.imports[?(@.slot == 'ADJUDICATED')].current").value(false))
                .andExpect(jsonPath("$.imports[?(@.slot == 'RATER_1' && @.current == true)].version").value(2));
        assertThat(itemRepository.findByBatchIdOrderByPositionAsc(batchId))
                .allMatch(item -> item.score(AnnotationSlot.ADJUDICATED) == null);
        assertThat(importRepository.findByBatchIdOrderByVersionAsc(batchId))
                .filteredOn(AnnotationImport::isCurrent)
                .extracting(AnnotationImport::getSlot)
                .containsExactlyInAnyOrder(AnnotationSlot.RATER_1, AnnotationSlot.RATER_2);
        mockMvc.perform(asResearcher(researcherId, get(base + "/" + batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicationCurrent").value(false));

        mockMvc.perform(asResearcher(researcherId, get(base)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(batchId.toString()));
        mockMvc.perform(asResearcher(otherResearcherId, get(base + "/" + batchId))).andExpect(status().isNotFound());

        assertThat(auditRepository.findByStudyIdOrderByCreatedAtDesc(study.getId()))
                .extracting(event -> event.getAction())
                .contains("ANNOTATION_BATCH_CREATED", "ANNOTATION_BATCH_EXPORTED", "ANNOTATION_IMPORTED",
                        "ANNOTATION_ADJUDICATION_INVALIDATED");
        assertThat(auditRepository.findByStudyIdOrderByCreatedAtDesc(study.getId()))
                .allSatisfy(event -> assertThat(String.valueOf(event.getDetail()))
                        .doesNotContain(codes.get(0), codes.get(1), "Hola", "P-00"));
    }

    @Test
    void semanticBatchExposesOnlyContextAndEvaluatedSuggestion() throws Exception {
        ExperimentRun assisted = completedRun(1, ExperimentCondition.ASSISTED, "final asistido", null);
        completedRun(2, ExperimentCondition.UNASSISTED, "final sin ayuda", null);
        transactionTemplate.executeWithoutResult(status -> {
            ExperimentRun run = runRepository.findById(assisted.getId()).orElseThrow();
            CorrectionSession accepted = new CorrectionSession(student, "ola mundo", run);
            accepted.complete("hola mundo", 0, "[\"hola, mundo\"]", 10L);
            accepted.registerFeedback("hola, mundo", null, true, 1, null);
            CorrectionSession rejected = new CorrectionSession(student, "=ke tal", run);
            rejected.complete("-que tal", 0, "[\"que tal?\"]", 10L);
            rejected.registerFeedback(null, null, false, 0, "UNDO");
            sessionRepository.saveAll(List.of(accepted, rejected));
        });

        JsonNode batch = json(mockMvc.perform(asResearcher(researcherId, post(base).param("kind", "SEMANTIC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rowCount").value(2)));
        UUID batchId = UUID.fromString(batch.get("id").asText());
        String csv = new String(mockMvc.perform(asResearcher(researcherId, get(base + "/" + batchId + "/export")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), UTF_8);
        assertThat(csv).startsWith("sample_code,original_text,suggestion,score\n")
                .contains(",ola mundo,\"hola, mundo\",\n")
                .contains(", =ke tal, -que tal,\n")
                .doesNotContain("que tal?", "final asistido", "final sin ayuda", "ASSISTED", "P-00", ",=ke", ",-que");

        List<String> codes = itemRepository.findByBatchIdOrderByPositionAsc(batchId).stream()
                .map(item -> item.getSampleCode()).toList();
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_1", "Ana",
                        "sample_code,score\n" + codes.get(0) + ",3\n" + codes.get(1) + ",0\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("0, 1 or 2")));
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_1", "Ana",
                        "sample_code,score\n" + codes.get(0) + ",2\n" + codes.get(1) + ",0\n")))
                .andExpect(status().isCreated());
    }

    // ----------------------------------------------------------------- helpers

    private ExperimentRun completedRun(int participantNumber, ExperimentCondition condition, String finalText, String exclusion) {
        return transactionTemplate.execute(status -> {
            StudyParticipant participant = participantRepository.save(new StudyParticipant(study, participantNumber));
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            ExperimentRun run = new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), condition,
                    AccessCode.hash("ABCD234" + participantNumber), clock.instant().plus(Duration.ofMinutes(30)),
                    clock.instant());
            run.redeem(clock.instant());
            run.start(clock.instant());
            run.complete(finalText, 60_000, UUID.randomUUID(), clock.instant());
            if (exclusion != null) {
                run.exclude(exclusion, researcherRepository.findById(researcherId).orElseThrow(), clock.instant());
            }
            return runRepository.save(run);
        });
    }

    private MockMultipartHttpServletRequestBuilder upload(UUID batchId, String slot, String rater, String csv) {
        MockMultipartHttpServletRequestBuilder builder = multipart(base + "/" + batchId + "/imports")
                .file(new MockMultipartFile("file", "scores.csv", "text/csv", csv.getBytes(UTF_8)));
        builder.param("slot", slot).param("rater", rater);
        return builder;
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<T>> T asResearcher(UUID id, T request) {
        return as("ROLE_RESEARCHER", id, request);
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(String role, T request) {
        return as(role, UUID.randomUUID(), request);
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(String role, UUID id, T request) {
        return request.with(jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority(role)));
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
