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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.TaskVariant;
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
class ResearchResultsApiTests {

    private static final String EVALUATIONS = "/api/v1/research/technical-evaluations";
    private static final String DATASET = "0123456789abcdef".repeat(4);

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
    private final Map<Integer, StudyParticipant> participants = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        Researcher researcher = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash"));
        researcherId = researcher.getId();
        otherResearcherId = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash")).getId();
        student = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash"));
        study = new ResearchStudy("EXP-" + UUID.randomUUID().toString().substring(0, 8), "Resultados", researcher);
        study.activate();
        study = studyRepository.save(study);
        protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Cuenta tu fin de semana");
        protocol.addTask(TaskVariant.TASK_B, "Describe tu escuela");
        protocol.activate();
        protocol = protocolRepository.save(protocol);
        base = "/api/v1/research/studies/" + study.getId();
    }

    @Test
    void teacherAndStudentAreForbiddenAndAnonymousIsUnauthorized() throws Exception {
        for (String role : List.of("ROLE_TEACHER", "ROLE_STUDENT")) {
            mockMvc.perform(as(role, get(base + "/results"))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, get(base + "/analysis.csv"))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, get(EVALUATIONS))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, get(EVALUATIONS + "/latest"))).andExpect(status().isForbidden());
            mockMvc.perform(as(role, post(EVALUATIONS)).contentType(MediaType.APPLICATION_JSON).content(evaluation(0.7142857)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get(base + "/results")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(base + "/analysis.csv")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EVALUATIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(EVALUATIONS).contentType(MediaType.APPLICATION_JSON).content(evaluation(0.7142857)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resultsAreStudyOwnedPseudonymousAndGatedOnAdjudication() throws Exception {
        // P-001: assisted 4 words / 2 min, unassisted 5 words / 1 min. P-002: assisted 2 words, unassisted 2 words.
        ExperimentRun p1Assisted = completedRun(1, ExperimentCondition.ASSISTED, "uno dos tres cuatro", 120_000, null);
        ExperimentRun p1Unassisted = completedRun(1, ExperimentCondition.UNASSISTED, "uno dos tres cuatro cinco", 60_000, null);
        ExperimentRun p2Assisted = completedRun(2, ExperimentCondition.ASSISTED, "uno dos", 60_000, null);
        ExperimentRun p2Unassisted = completedRun(2, ExperimentCondition.UNASSISTED, "uno dos", 60_000, null);
        ExperimentRun p3Assisted = completedRun(3, ExperimentCondition.ASSISTED, "solo una condicion", 60_000, null);
        ExperimentRun p4Assisted = completedRun(4, ExperimentCondition.ASSISTED, "texto", 60_000, null);
        completedRun(4, ExperimentCondition.UNASSISTED, "excluido", 60_000, "Excluded for a documented reason");
        transactionTemplate.executeWithoutResult(status -> {
            ExperimentRun run = runRepository.findById(p1Assisted.getId()).orElseThrow();
            CorrectionSession accepted = new CorrectionSession(student, "ola mundo", run);
            accepted.complete("hola mundo", 0, "[\"hola, mundo\"]", 10L);
            accepted.registerFeedback("hola, mundo", null, true, 1, null);
            CorrectionSession rejected = new CorrectionSession(student, "ke tal", run);
            rejected.complete("que tal", 0, "[]", 10L);
            rejected.registerFeedback(null, null, false, 0, "UNDO");
            sessionRepository.saveAll(List.of(accepted, rejected));
        });

        // Other researcher: unknown and unowned are the same 404.
        mockMvc.perform(asResearcher(otherResearcherId, get(base + "/results")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Study not found"));
        mockMvc.perform(asResearcher(otherResearcherId, get(base + "/analysis.csv"))).andExpect(status().isNotFound());
        mockMvc.perform(asResearcher(researcherId, get("/api/v1/research/studies/" + UUID.randomUUID() + "/results")))
                .andExpect(status().isNotFound());

        // Before any annotation: sample and PPM, but PEO/TAS are null with an explicit status.
        JsonNode before = json(mockMvc.perform(asResearcher(researcherId, get(base + "/results")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sample.participantsTotal").value(4))
                .andExpect(jsonPath("$.sample.participantsIncluded").value(2))
                .andExpect(jsonPath("$.sample.participantsWithIncompletePair").value(2))
                .andExpect(jsonPath("$.sample.runsCompleted").value(7))
                .andExpect(jsonPath("$.sample.runsIncluded").value(4))
                .andExpect(jsonPath("$.sample.runsExcluded").value(1))
                .andExpect(jsonPath("$.sample.runsInIncompletePairs").value(2))
                .andExpect(jsonPath("$.peo").doesNotExist())
                .andExpect(jsonPath("$.tas").doesNotExist())
                .andExpect(jsonPath("$.orthographyAnnotation.status").value("NO_BATCH"))
                .andExpect(jsonPath("$.semanticAnnotation.status").value("NO_BATCH"))
                .andExpect(jsonPath("$.ppm.descriptive").value(true))
                .andExpect(jsonPath("$.ppm.nonInferior").doesNotExist())
                .andExpect(jsonPath("$.ppm.paired.n").value(2))
                .andExpect(jsonPath("$.ppm.paired.meanDelta").value(-1.5))
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].pseudonym").value("P-001"))
                .andExpect(jsonPath("$.participants[1].pseudonym").value("P-002"))
                .andExpect(jsonPath("$.provenance.protocolVersions[0]").value(1))
                .andExpect(jsonPath("$.provenance.datasets").isEmpty())
                .andExpect(jsonPath("$.provenance.computedAt").exists()));
        assertNoIdentity(before);

        // Orthography: create, download, two raters, adjudication (scores keyed by the run of each sample).
        UUID orthographyBatch = annotate("ORTHOGRAPHY", Map.of(
                p1Assisted.getId(), 1, p1Unassisted.getId(), 2, p2Assisted.getId(), 0, p2Unassisted.getId(), 0,
                p3Assisted.getId(), 0, p4Assisted.getId(), 0));
        JsonNode withPeo = json(mockMvc.perform(asResearcher(researcherId, get(base + "/results")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orthographyAnnotation.status").value("ADJUDICATED"))
                .andExpect(jsonPath("$.orthographyAnnotation.batchId").value(orthographyBatch.toString()))
                .andExpect(jsonPath("$.peo.paired.n").value(2))
                .andExpect(jsonPath("$.peo.paired.assistedMean").value(12.5))
                .andExpect(jsonPath("$.peo.paired.unassistedMean").value(20.0))
                .andExpect(jsonPath("$.peo.paired.meanDelta").value(-7.5))
                .andExpect(jsonPath("$.peo.relativeReductionMean").value(37.5))
                .andExpect(jsonPath("$.peo.relativeReductionSkipped").value(1))
                .andExpect(jsonPath("$.participants[0].peoAssisted").value(25.0))
                .andExpect(jsonPath("$.participants[0].peoUnassisted").value(40.0))
                .andExpect(jsonPath("$.provenance.datasets.length()").value(1))
                .andExpect(jsonPath("$.provenance.datasets[0].kind").value("ORTHOGRAPHY"))
                .andExpect(jsonPath("$.provenance.datasets[0].adjudicationSha256").isString()));
        assertNoIdentity(withPeo);

        // Semantic: the accepted suggestion is harmful (0); the rejected one is safe (2).
        UUID semanticBatch = annotateSemantic(Map.of("ola mundo", 0, "ke tal", 2));
        json(mockMvc.perform(asResearcher(researcherId, get(base + "/results")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semanticAnnotation.status").value("ADJUDICATED"))
                .andExpect(jsonPath("$.semanticAnnotation.batchId").value(semanticBatch.toString()))
                .andExpect(jsonPath("$.tas.suggestionsEvaluated").value(2))
                .andExpect(jsonPath("$.tas.harmfulSuggestions").value(1))
                .andExpect(jsonPath("$.tas.pooledRate").value(50.0))
                .andExpect(jsonPath("$.tas.participantsEvaluated").value(1))
                .andExpect(jsonPath("$.tas.participantsWithoutDenominator").value(1))
                .andExpect(jsonPath("$.tas.descriptive").value(true))
                .andExpect(jsonPath("$.tasAccepted.suggestionsEvaluated").value(1))
                .andExpect(jsonPath("$.tasAccepted.harmfulSuggestions").value(1))
                .andExpect(jsonPath("$.tasAccepted.pooledRate").value(100.0))
                .andExpect(jsonPath("$.participants[0].tas").value(50.0))
                .andExpect(jsonPath("$.participants[0].tasAccepted").value(100.0))
                .andExpect(jsonPath("$.participants[1].tas").doesNotExist())
                .andExpect(jsonPath("$.provenance.datasets.length()").value(2)));

        // The analysis export carries pseudonyms, texts and adjudicated values, never the account.
        MvcResult download = mockMvc.perform(asResearcher(researcherId, get(base + "/analysis.csv")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        String csv = new String(download.getResponse().getContentAsByteArray(), UTF_8);
        assertThat(csv).startsWith("pseudonym,condition,task,protocol_version,included,excluded,run_id,")
                .contains("P-001,ASSISTED,TASK_A,1,true,false," + p1Assisted.getId() + ",120000,4,1,uno dos tres cuatro,1,ola mundo,\"hola, mundo\",0,true\n")
                .contains("P-001,ASSISTED,TASK_A,1,true,false," + p1Assisted.getId() + ",120000,4,1,uno dos tres cuatro,0,ke tal,que tal,2,false\n")
                .contains("P-001,UNASSISTED,TASK_A,1,true,false," + p1Unassisted.getId() + ",60000,5,2,uno dos tres cuatro cinco,,,,,\n")
                .contains("P-004,UNASSISTED,TASK_A,1,false,true,")
                .doesNotContain("alumno-", "Colegio", "@lab.edu", "T-");
        assertThat(download.getResponse().getHeader("X-Content-SHA256")).matches("[0-9a-f]{64}");
        assertThat(auditRepository.findByStudyIdOrderByCreatedAtDesc(study.getId()))
                .anySatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo("STUDY_RESULTS_EXPORTED");
                    assertThat(String.valueOf(event.getDetail())).doesNotContain("uno dos", "P-001");
                });
    }

    @Test
    void technicalEvaluationsAreValidatedVersionedAndPerResearcher() throws Exception {
        mockMvc.perform(asResearcher(researcherId, post(EVALUATIONS)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation("t5-lora-global-v3", "ABC", "exact_token_edits_v1", 0.8, 0.5, 0.7142857, 40, 10, 40)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.datasetSha256").exists());
        mockMvc.perform(asResearcher(researcherId, post(EVALUATIONS)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation("t5-lora-global-v3", DATASET, "other_scorer", 0.8, 0.5, 0.7142857, 40, 10, 40)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.scorerVersion").exists());
        mockMvc.perform(asResearcher(researcherId, post(EVALUATIONS)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation("t5-lora-global-v3", DATASET, "exact_token_edits_v1", 1.2, 0.5, 0.7142857, 40, -1, 40)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.precision").exists())
                .andExpect(jsonPath("$.validationErrors.falsePositives").exists());
        mockMvc.perform(asResearcher(researcherId, post(EVALUATIONS)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(0.71)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("F0.5 does not match")));
        mockMvc.perform(asResearcher(researcherId, get(EVALUATIONS + "/latest"))).andExpect(status().isNotFound());

        JsonNode created = json(mockMvc.perform(asResearcher(researcherId, post(EVALUATIONS))
                        .contentType(MediaType.APPLICATION_JSON).content(evaluation(0.7142857)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modelVersion").value("t5-lora-global-v3"))
                .andExpect(jsonPath("$.datasetSha256").value(DATASET))
                .andExpect(jsonPath("$.scorerVersion").value("exact_token_edits_v1"))
                .andExpect(jsonPath("$.fZeroFive").value(0.7142857))
                .andExpect(jsonPath("$.fOne").value(org.hamcrest.Matchers.closeTo(0.6153846, 1e-6)))
                .andExpect(jsonPath("$.truePositives").value(40))
                .andExpect(jsonPath("$.createdAt").exists()));
        assertThat(created.toString()).doesNotContain("@lab.edu", "createdBy");
        mockMvc.perform(asResearcher(researcherId, post(EVALUATIONS)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation("baseline-beto", DATASET, "exact_token_edits_v1", 0.5, 0.5, 0.5, 5, 5, 5)))
                .andExpect(status().isCreated());

        mockMvc.perform(asResearcher(researcherId, get(EVALUATIONS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelVersion").value("baseline-beto"))
                .andExpect(jsonPath("$[1].id").value(created.get("id").asText()));
        mockMvc.perform(asResearcher(researcherId, get(EVALUATIONS).param("modelVersion", "t5-lora-global-v3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(created.get("id").asText()));
        mockMvc.perform(asResearcher(researcherId, get(EVALUATIONS + "/latest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("baseline-beto"));
        mockMvc.perform(asResearcher(researcherId, get(EVALUATIONS + "/latest").param("modelVersion", "t5-lora-global-v3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.get("id").asText()));

        // Another researcher sees only their own evaluations.
        mockMvc.perform(asResearcher(otherResearcherId, get(EVALUATIONS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(asResearcher(otherResearcherId, get(EVALUATIONS + "/latest"))).andExpect(status().isNotFound());

        // F0.5 never appears in the study results object.
        String results = mockMvc.perform(asResearcher(researcherId, get(base + "/results")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(results).doesNotContain("fZeroFive", "precision", "recall", "t5-lora-global-v3");
    }

    // ----------------------------------------------------------------- helpers

    /** Creates a batch, uploads two raters and the adjudication with the given score per run; returns the batch id. */
    private UUID annotate(String kind, Map<UUID, Integer> scoreByRun) throws Exception {
        String batches = base + "/annotation-batches";
        JsonNode batch = json(mockMvc.perform(asResearcher(researcherId, post(batches).param("kind", kind)))
                .andExpect(status().isCreated()));
        UUID batchId = UUID.fromString(batch.get("id").asText());
        String csv = transactionTemplate.execute(status -> {
            StringBuilder content = new StringBuilder("sample_code,score\n");
            itemRepository.findByBatchIdOrderByPositionAsc(batchId).forEach(item ->
                    content.append(item.getSampleCode()).append(',').append(scoreByRun.get(item.getRun().getId())).append('\n'));
            return content.toString();
        });
        importAll(batchId, csv);
        return batchId;
    }

    private UUID annotateSemantic(Map<String, Integer> scoreByOriginalText) throws Exception {
        String batches = base + "/annotation-batches";
        JsonNode batch = json(mockMvc.perform(asResearcher(researcherId, post(batches).param("kind", "SEMANTIC")))
                .andExpect(status().isCreated()));
        UUID batchId = UUID.fromString(batch.get("id").asText());
        String csv = transactionTemplate.execute(status -> {
            StringBuilder content = new StringBuilder("sample_code,score\n");
            itemRepository.findByBatchIdOrderByPositionAsc(batchId).forEach(item -> content.append(item.getSampleCode())
                    .append(',').append(scoreByOriginalText.get(item.getCorrectionSession().getOriginalText())).append('\n'));
            return content.toString();
        });
        importAll(batchId, csv);
        return batchId;
    }

    private void importAll(UUID batchId, String csv) throws Exception {
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_1", "Ana", csv))).andExpect(status().isCreated());
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "RATER_2", "Beto", csv))).andExpect(status().isCreated());
        mockMvc.perform(asResearcher(researcherId, upload(batchId, "ADJUDICATED", "Consenso", csv)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjudicationCurrent").value(true));
    }

    private ExperimentRun completedRun(
            int participantNumber, ExperimentCondition condition, String finalText, long durationMs, String exclusion) {
        return transactionTemplate.execute(status -> {
            StudyParticipant participant = participants.computeIfAbsent(participantNumber,
                    number -> participantRepository.save(new StudyParticipant(study, number)));
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            ExperimentRun run = new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), condition,
                    AccessCode.hash("ABCD234" + participantNumber + condition), clock.instant().plus(Duration.ofMinutes(30)),
                    clock.instant());
            run.redeem(clock.instant());
            run.start(clock.instant());
            run.complete(finalText, durationMs, UUID.randomUUID(), clock.instant());
            if (exclusion != null) {
                run.exclude(exclusion, researcherRepository.findById(researcherId).orElseThrow(), clock.instant());
            }
            return runRepository.save(run);
        });
    }

    private MockMultipartHttpServletRequestBuilder upload(UUID batchId, String slot, String rater, String csv) {
        MockMultipartHttpServletRequestBuilder builder = multipart(base + "/annotation-batches/" + batchId + "/imports")
                .file(new MockMultipartFile("file", "scores.csv", "text/csv", csv.getBytes(UTF_8)));
        builder.param("slot", slot).param("rater", rater);
        return builder;
    }

    private static String evaluation(double fZeroFive) {
        return evaluation("t5-lora-global-v3", DATASET, "exact_token_edits_v1", 0.8, 0.5, fZeroFive, 40, 10, 40);
    }

    private static String evaluation(String model, String dataset, String scorer, double p, double r, double f,
                                     int tp, int fp, int fn) {
        return String.format(java.util.Locale.ROOT,
                "{\"modelVersion\":\"%s\",\"datasetSha256\":\"%s\",\"scorerVersion\":\"%s\",\"precision\":%s,"
                        + "\"recall\":%s,\"fZeroFive\":%s,\"truePositives\":%d,\"falsePositives\":%d,\"falseNegatives\":%d}",
                model, dataset, scorer, p, r, f, tp, fp, fn);
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

    private static void assertNoIdentity(JsonNode node) {
        String text = node.toString().toLowerCase();
        assertThat(text).doesNotContain("alumno-", "colegio", "@lab.edu", "\"student", "\"email", "\"name\"");
    }
}
