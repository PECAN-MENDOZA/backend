package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.sentencetest.application.dto.AssignTestRequest;
import com.mvp.backend.sentencetest.application.dto.AssignmentStatusResponse;
import com.mvp.backend.sentencetest.application.dto.AttemptDetailResponse;
import com.mvp.backend.sentencetest.application.dto.CreateTestRequest;
import com.mvp.backend.sentencetest.application.dto.SentenceInput;
import com.mvp.backend.sentencetest.application.dto.TestDetailResponse;
import com.mvp.backend.sentencetest.application.dto.UpdateTestRequest;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAssignment;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.SentenceTestRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAssignmentRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestResponseRepository;
import com.mvp.backend.sentencetest.domain.repository.TestSentenceRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class ResearchTestServiceTests {

    @Mock private SentenceTestRepository testRepository;
    @Mock private TestSentenceRepository sentenceRepository;
    @Mock private TestAssignmentRepository assignmentRepository;
    @Mock private TestAttemptRepository attemptRepository;
    @Mock private TestResponseRepository responseRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private ClassroomRepository classroomRepository;
    @Mock private TeacherStudentLinkRepository linkRepository;

    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");
    private final UUID researcherId = UUID.randomUUID();
    private ResearchTestService service;
    private SentenceTest draft;
    private SentenceTest active;
    private Student student;
    private List<TestSentence> sentences;

    @BeforeEach
    void setUp() {
        service = new ResearchTestService(testRepository, sentenceRepository, assignmentRepository, attemptRepository,
                responseRepository, studentRepository, classroomRepository, linkRepository,
                Clock.fixed(now, ZoneOffset.UTC));
        draft = new SentenceTest("BORRADOR-01", "Borrador", researcherId);
        active = new SentenceTest("PRUEBA-01", "Dictado", researcherId);
        active.activate(2, now);
        student = new Student("tigre-07", "Colegio", "hash");
        sentences = List.of(
                new TestSentence(active, 1, SentenceKind.DICTATED, "El perro corre.", Assistance.ASSISTED),
                new TestSentence(active, 2, SentenceKind.FREE, "Una oracion sobre tu mascota", Assistance.UNASSISTED));
    }

    // --- creacion ---

    @Test
    void createRejectsInvalidCode() {
        assertThatThrownBy(() -> service.createTest(researcherId, new CreateTestRequest("prueba 1", "Titulo", null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("code");
        verify(testRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsDuplicateCode() {
        when(testRepository.existsByCode("PRUEBA-01")).thenReturn(true);

        assertThatThrownBy(() -> service.createTest(researcherId, new CreateTestRequest("PRUEBA-01", "Titulo", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createSavesDraftWithTrimmedFields() {
        when(testRepository.existsByCode("PRUEBA-02")).thenReturn(false);
        when(testRepository.saveAndFlush(any(SentenceTest.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createTest(researcherId, new CreateTestRequest("PRUEBA-02", "  Dictado 2  ", " notas "));

        assertThat(response.code()).isEqualTo("PRUEBA-02");
        assertThat(response.title()).isEqualTo("Dictado 2");
        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.sentenceCount()).isZero();
        assertThat(response.assignedCount()).isZero();
        assertThat(response.completedCount()).isZero();
    }

    // --- edicion ---

    @Test
    void updateOnActiveTestIsConflict() {
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.updateTest(active.getId(), new UpdateTestRequest("Titulo", null, List.of(dictated("Hola.")))))
                .isInstanceOf(ConflictException.class);
        verify(sentenceRepository, never()).deleteByTestId(any());
    }

    @Test
    void updateWithoutSentencesIsRejected() {
        when(testRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.updateTest(draft.getId(), new UpdateTestRequest("Titulo", null, List.of())))
                .isInstanceOf(BusinessException.class);
        verify(sentenceRepository, never()).deleteByTestId(any());
    }

    @Test
    void updateRejectsBlankReferenceAndUnknownEnum() {
        when(testRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.updateTest(draft.getId(),
                new UpdateTestRequest("Titulo", null, List.of(new SentenceInput("DICTATED", "   ", "ASSISTED")))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.updateTest(draft.getId(),
                new UpdateTestRequest("Titulo", null, List.of(new SentenceInput("ORAL", "Hola.", "ASSISTED")))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateReplacesSentencesKeepingOrderAndPositions() {
        when(testRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(sentenceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(sentenceRepository.findByTestIdOrderByPositionAsc(draft.getId())).thenAnswer(inv -> List.of(
                new TestSentence(draft, 1, SentenceKind.DICTATED, "Uno.", Assistance.ASSISTED),
                new TestSentence(draft, 2, SentenceKind.FREE, "Dos", Assistance.ASSISTED),
                new TestSentence(draft, 3, SentenceKind.DICTATED, "Tres.", Assistance.UNASSISTED)));

        TestDetailResponse detail = service.updateTest(draft.getId(), new UpdateTestRequest("Nuevo titulo", "n", List.of(
                dictated("Uno."), new SentenceInput("FREE", "Dos", "ASSISTED"), new SentenceInput("DICTATED", " Tres. ", "UNASSISTED"))));

        verify(sentenceRepository).deleteByTestId(draft.getId());
        verify(sentenceRepository).flush();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestSentence>> captor = ArgumentCaptor.forClass(List.class);
        verify(sentenceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(TestSentence::getPosition).containsExactly(1, 2, 3);
        assertThat(captor.getValue()).extracting(TestSentence::getReferenceText).containsExactly("Uno.", "Dos", "Tres.");
        assertThat(draft.getTitle()).isEqualTo("Nuevo titulo");
        assertThat(detail.sentenceCount()).isEqualTo(3);
        assertThat(detail.counts().dictated()).isEqualTo(2);
        assertThat(detail.counts().free()).isEqualTo(1);
        assertThat(detail.counts().assisted()).isEqualTo(2);
        assertThat(detail.counts().unassisted()).isEqualTo(1);
    }

    @Test
    void activateWithoutSentencesIsRejected() {
        when(testRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(sentenceRepository.countByTestId(draft.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.activateTest(draft.getId())).isInstanceOf(BusinessException.class);
        assertThat(draft.getStatus().name()).isEqualTo("DRAFT");
    }

    // --- asignacion ---

    @Test
    void assignByClassroomCreatesOneAssignmentPerUnassignedStudent() {
        Teacher teacher = new Teacher("doc", "d@c.edu", null, "C", "hash", researcherId, true, null);
        Classroom classroom = new Classroom(teacher, "3 B");
        Student already = new Student("puma-01", "Colegio", "hash");
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(classroomRepository.findById(classroom.getId())).thenReturn(Optional.of(classroom));
        when(linkRepository.findByClassroomIdAndDeletedAtIsNullOrderByCreatedAtAsc(classroom.getId())).thenReturn(List.of(
                new TeacherStudentLink(teacher, student, classroom, "enc", null),
                new TeacherStudentLink(teacher, already, classroom, "enc", null)));
        when(assignmentRepository.existsByTestIdAndStudentId(active.getId(), student.getId())).thenReturn(false);
        when(assignmentRepository.existsByTestIdAndStudentId(active.getId(), already.getId())).thenReturn(true);
        when(assignmentRepository.save(any(TestAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.findByTestIdOrderByAssignedAtAsc(active.getId())).thenReturn(List.of(
                new TestAssignment(active, already, classroom.getId(), researcherId, now),
                new TestAssignment(active, student, classroom.getId(), researcherId, now)));
        when(attemptRepository.findByTestIdOrderByStartedAtAsc(active.getId())).thenReturn(List.of());
        when(sentenceRepository.countByTestId(active.getId())).thenReturn(2L);

        List<AssignmentStatusResponse> rows =
                service.assign(researcherId, active.getId(), new AssignTestRequest(classroom.getId(), null));

        ArgumentCaptor<TestAssignment> captor = ArgumentCaptor.forClass(TestAssignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStudent()).isSameAs(student);
        assertThat(captor.getValue().getClassroomId()).isEqualTo(classroom.getId());
        assertThat(captor.getValue().getAssignedBy()).isEqualTo(researcherId);
        assertThat(rows).extracting(AssignmentStatusResponse::studentUsername).containsExactly("puma-01", "tigre-07");
        assertThat(rows).extracting(AssignmentStatusResponse::attemptStatus).containsOnly("PENDING");
        assertThat(rows.get(0).sentenceCount()).isEqualTo(2);
        assertThat(rows.get(0).currentPosition()).isNull();
    }

    @Test
    void assignRequiresExactlyOneTarget() {
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.assign(researcherId, active.getId(), new AssignTestRequest(null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.assign(researcherId, active.getId(),
                new AssignTestRequest(UUID.randomUUID(), List.of(UUID.randomUUID()))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void assignOnDraftIsConflictAndUnknownClassroomIsNotFound() {
        when(testRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.assign(researcherId, draft.getId(), new AssignTestRequest(null, List.of(student.getId()))))
                .isInstanceOf(ConflictException.class);

        UUID classroomId = UUID.randomUUID();
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(classroomRepository.findById(classroomId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(researcherId, active.getId(), new AssignTestRequest(classroomId, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void assignToArchivedClassroomIsRejected() {
        Teacher teacher = new Teacher("doc", "d@c.edu", null, "C", "hash", researcherId, true, null);
        Classroom archived = new Classroom(teacher, "3 B");
        archived.archive();
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(classroomRepository.findById(archived.getId())).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.assign(researcherId, active.getId(), new AssignTestRequest(archived.getId(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cannot assign an archived classroom");
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void excludingTwiceKeepsTheFirstReasonAndAuthor() {
        TestAttempt attempt = new TestAttempt(active, student, "app", "b", now);
        attempt.complete(now);
        UUID firstResearcher = UUID.randomUUID();
        attempt.exclude("Motivo original suficientemente largo", firstResearcher, now.minusSeconds(60));
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.excludeAttempt(researcherId, active.getId(), attempt.getId(),
                "Otro motivo que no debe pisar al primero"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Attempt already excluded");
        assertThat(attempt.getExclusionReason()).isEqualTo("Motivo original suficientemente largo");
        assertThat(attempt.getExcludedBy()).isEqualTo(firstResearcher);
        assertThat(attempt.getExcludedAt()).isEqualTo(now.minusSeconds(60));
    }

    @Test
    void assignmentsReportCurrentPositionAsFinishedPlusOne() {
        TestAttempt inProgress = new TestAttempt(active, student, "app", "b", now);
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(assignmentRepository.findByTestIdOrderByAssignedAtAsc(active.getId()))
                .thenReturn(List.of(new TestAssignment(active, student, null, researcherId, now)));
        when(attemptRepository.findByTestIdOrderByStartedAtAsc(active.getId())).thenReturn(List.of(inProgress));
        when(sentenceRepository.countByTestId(active.getId())).thenReturn(2L);
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(inProgress.getId())).thenReturn(1L);

        List<AssignmentStatusResponse> rows = service.assignments(active.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).attemptId()).isEqualTo(inProgress.getId());
        assertThat(rows.get(0).attemptStatus()).isEqualTo("IN_PROGRESS");
        assertThat(rows.get(0).currentPosition()).isEqualTo(2);
        assertThat(rows.get(0).excluded()).isFalse();
    }

    @Test
    void assignmentsUseTheNewestAttemptAndFlagExclusion() {
        TestAttempt cancelled = new TestAttempt(active, student, "app", "b", now.minusSeconds(600));
        cancelled.cancel(com.mvp.backend.sentencetest.domain.model.AttemptCancelReason.ABANDONED, now.minusSeconds(500));
        TestAttempt completed = new TestAttempt(active, student, "app", "b", now.minusSeconds(100));
        completed.complete(now);
        completed.exclude("Se distrajo durante la prueba", researcherId, now);
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(assignmentRepository.findByTestIdOrderByAssignedAtAsc(active.getId()))
                .thenReturn(List.of(new TestAssignment(active, student, null, researcherId, now)));
        when(attemptRepository.findByTestIdOrderByStartedAtAsc(active.getId())).thenReturn(List.of(cancelled, completed));
        when(sentenceRepository.countByTestId(active.getId())).thenReturn(2L);

        AssignmentStatusResponse row = service.assignments(active.getId()).get(0);

        assertThat(row.attemptId()).isEqualTo(completed.getId());
        assertThat(row.attemptStatus()).isEqualTo("COMPLETED");
        assertThat(row.currentPosition()).isNull();
        assertThat(row.excluded()).isTrue();
    }

    // --- detalle, exclusion y anotacion ---

    @Test
    void attemptDetailComputesWordCountAndErrorSource() {
        TestAttempt attempt = new TestAttempt(active, student, "app-1", "b-1", now);
        TestResponse dictated = new TestResponse(attempt, sentences.get(0), now);
        dictated.finish("El pero corre.", 100L, 5000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), now);
        dictated.recordAutoErrors(1, "{\"error_count\":1}");
        TestResponse free = new TestResponse(attempt, sentences.get(1), now);
        free.finish("Mi gato duerme mucho", 200L, 8000L, false, new TestResponse.Counters(3, 1, 1, 1), UUID.randomUUID(), now);
        attempt.complete(now);
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(responseRepository.findByAttemptIdOrderByPositionAsc(attempt.getId())).thenReturn(List.of(dictated, free));

        AttemptDetailResponse detail = service.attemptDetail(active.getId(), attempt.getId());

        assertThat(detail.studentUsername()).isEqualTo("tigre-07");
        assertThat(detail.status()).isEqualTo("COMPLETED");
        assertThat(detail.responses()).hasSize(2);
        AttemptDetailResponse.ResponseRow first = detail.responses().get(0);
        assertThat(first.kind()).isEqualTo("DICTATED");
        assertThat(first.wordCount()).isEqualTo(3);
        assertThat(first.autoErrorCount()).isEqualTo(1);
        assertThat(first.effectiveErrorCount()).isEqualTo(1);
        assertThat(first.errorSource()).isEqualTo("AUTO");
        assertThat(first.durationFromFirstKeyMs()).isEqualTo(4900L);
        AttemptDetailResponse.ResponseRow second = detail.responses().get(1);
        assertThat(second.kind()).isEqualTo("FREE");
        assertThat(second.wordCount()).isEqualTo(4);
        assertThat(second.autoErrorCount()).isNull();
        assertThat(second.effectiveErrorCount()).isNull();
        assertThat(second.errorSource()).isEqualTo("PENDING");
        assertThat(second.suggestionsOffered()).isEqualTo(3);
    }

    @Test
    void attemptOfAnotherTestIsNotFound() {
        TestAttempt attempt = new TestAttempt(active, student, "app", "b", now);
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.attemptDetail(draft.getId(), attempt.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void excludeWithShortReasonIsRejected() {
        TestAttempt attempt = new TestAttempt(active, student, "app", "b", now);
        attempt.complete(now);
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.excludeAttempt(researcherId, active.getId(), attempt.getId(), "corto"))
                .isInstanceOf(BusinessException.class);
        assertThat(attempt.isExcluded()).isFalse();
    }

    @Test
    void excludeRecordsReasonAndResearcher() {
        TestAttempt attempt = new TestAttempt(active, student, "app", "b", now);
        attempt.complete(now);
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(responseRepository.findByAttemptIdOrderByPositionAsc(attempt.getId())).thenReturn(List.of());

        AttemptDetailResponse detail = service.excludeAttempt(researcherId, active.getId(), attempt.getId(),
                "Interrumpido por el timbre del recreo");

        assertThat(detail.excludedAt()).isEqualTo(now);
        assertThat(detail.exclusionReason()).isEqualTo("Interrumpido por el timbre del recreo");
        assertThat(attempt.getExcludedBy()).isEqualTo(researcherId);
    }

    @Test
    void annotateDictatedIsRejected() {
        TestAttempt attempt = new TestAttempt(active, student, "app", "b", now);
        attempt.complete(now);
        TestResponse dictated = new TestResponse(attempt, sentences.get(0), now);
        when(responseRepository.findById(dictated.getId())).thenReturn(Optional.of(dictated));

        assertThatThrownBy(() -> service.annotate(researcherId, dictated.getId(), 2))
                .isInstanceOf(BusinessException.class).hasMessageContaining("free");
    }

    @Test
    void annotateFreeOnAttemptInProgressIsConflict() {
        TestAttempt attempt = new TestAttempt(active, student, "app", "b", now);
        TestResponse free = new TestResponse(attempt, sentences.get(1), now);
        when(responseRepository.findById(free.getId())).thenReturn(Optional.of(free));

        assertThatThrownBy(() -> service.annotate(researcherId, free.getId(), 2))
                .isInstanceOf(ConflictException.class).hasMessageContaining("not completed");
        assertThat(free.getAnnotatedErrorCount()).isNull();
    }

    @Test
    void annotateFreeOnCompletedAttemptStoresCountAndResearcher() {
        TestAttempt attempt = new TestAttempt(active, student, "app", "b", now);
        TestResponse free = new TestResponse(attempt, sentences.get(1), now);
        free.finish("Mi gato duerme", 100L, 4000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), now);
        attempt.complete(now);
        when(responseRepository.findById(free.getId())).thenReturn(Optional.of(free));

        AttemptDetailResponse.ResponseRow row = service.annotate(researcherId, free.getId(), 2);

        assertThat(free.getAnnotatedErrorCount()).isEqualTo(2);
        assertThat(free.getAnnotatedBy()).isEqualTo(researcherId);
        assertThat(free.getAnnotatedAt()).isEqualTo(now);
        assertThat(row.responseId()).isEqualTo(free.getId());
        assertThat(row.annotatedErrorCount()).isEqualTo(2);
        assertThat(row.effectiveErrorCount()).isEqualTo(2);
        assertThat(row.errorSource()).isEqualTo("ANNOTATED");
        assertThat(row.wordCount()).isEqualTo(3);
    }

    // --- cohorte para resultados ---

    @Test
    void loadCohortSeparatesExcludedAttemptsButKeepsTheirResponses() {
        Student other = new Student("puma-02", "Colegio", "hash");
        TestAttempt kept = new TestAttempt(active, student, "app", "b", now);
        kept.complete(now);
        TestAttempt excluded = new TestAttempt(active, other, "app", "b", now);
        excluded.complete(now);
        excluded.exclude("Motivo suficientemente largo", researcherId, now);
        TestAttempt running = new TestAttempt(active, new Student("oso-03", "Colegio", "hash"), "app", "b", now);
        TestResponse keptResponse = new TestResponse(kept, sentences.get(0), now);
        TestResponse excludedResponse = new TestResponse(excluded, sentences.get(0), now);
        when(testRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(sentenceRepository.findByTestIdOrderByPositionAsc(active.getId())).thenReturn(sentences);
        when(attemptRepository.findByTestIdOrderByStartedAtAsc(active.getId())).thenReturn(List.of(kept, excluded, running));
        when(responseRepository.findByAttemptIdInOrderByAttemptIdAscPositionAsc(List.of(kept.getId(), excluded.getId())))
                .thenReturn(List.of(keptResponse, excludedResponse));

        ResearchTestService.Cohort cohort = service.loadCohort(active.getId());

        assertThat(cohort.test()).isSameAs(active);
        assertThat(cohort.sentences()).isEqualTo(sentences);
        assertThat(cohort.completedNotExcluded()).containsExactly(kept);
        assertThat(cohort.completedAll()).containsExactly(kept, excluded);
        assertThat(cohort.responsesByAttempt()).containsOnlyKeys(kept.getId(), excluded.getId());
        assertThat(cohort.responsesByAttempt().get(excluded.getId())).containsExactly(excludedResponse);
        assertThat(cohort.usernameByStudent()).containsEntry(student.getId(), "tigre-07").containsEntry(other.getId(), "puma-02");
        assertThat(cohort.usernameByStudent()).hasSize(2);
    }

    private static SentenceInput dictated(String text) {
        return new SentenceInput("DICTATED", text, "ASSISTED");
    }
}
