package com.mvp.backend.sentencetest.application.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.sentencetest.application.dto.AssignTestRequest;
import com.mvp.backend.sentencetest.application.dto.AssignmentStatusResponse;
import com.mvp.backend.sentencetest.application.dto.AttemptDetailResponse;
import com.mvp.backend.sentencetest.application.dto.CreateTestRequest;
import com.mvp.backend.sentencetest.application.dto.SentenceInput;
import com.mvp.backend.sentencetest.application.dto.TestDetailResponse;
import com.mvp.backend.sentencetest.application.dto.TestSummaryResponse;
import com.mvp.backend.sentencetest.application.dto.UpdateTestRequest;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
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
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

/**
 * Lado del investigador: redactar pruebas, activarlas y cerrarlas, asignarlas a salones o alumnos,
 * revisar intentos, excluirlos y anotar las oraciones libres. Nunca expone nombres reales.
 */
@Service
public class ResearchTestService {

    /**
     * Cohorte de una prueba para resultados y exportacion (Task 6). responsesByAttempt y
     * usernameByStudent cubren todos los intentos completados, tambien los excluidos, para que
     * el CSV pueda marcarlos; completedNotExcluded es la muestra analizable.
     */
    public record Cohort(
            SentenceTest test,
            List<TestSentence> sentences,
            List<TestAttempt> completedNotExcluded,
            Map<UUID, List<TestResponse>> responsesByAttempt,
            Map<UUID, String> usernameByStudent,
            List<TestAttempt> completedAll) {

        /** Cohorte sin intentos excluidos: completedAll coincide con completedNotExcluded. */
        public Cohort(SentenceTest test, List<TestSentence> sentences, List<TestAttempt> completedNotExcluded,
                Map<UUID, List<TestResponse>> responsesByAttempt, Map<UUID, String> usernameByStudent) {
            this(test, sentences, completedNotExcluded, responsesByAttempt, usernameByStudent, completedNotExcluded);
        }
    }

    public static final int MAX_SENTENCES = 60;
    public static final int MAX_REFERENCE_LENGTH = 500;
    public static final int MAX_TITLE_LENGTH = 120;
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9-]{3,40}$");
    private static final String PENDING = "PENDING";

    private final SentenceTestRepository testRepository;
    private final TestSentenceRepository sentenceRepository;
    private final TestAssignmentRepository assignmentRepository;
    private final TestAttemptRepository attemptRepository;
    private final TestResponseRepository responseRepository;
    private final StudentRepository studentRepository;
    private final ClassroomRepository classroomRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final Clock clock;

    public ResearchTestService(
            SentenceTestRepository testRepository,
            TestSentenceRepository sentenceRepository,
            TestAssignmentRepository assignmentRepository,
            TestAttemptRepository attemptRepository,
            TestResponseRepository responseRepository,
            StudentRepository studentRepository,
            ClassroomRepository classroomRepository,
            TeacherStudentLinkRepository linkRepository,
            Clock clock) {
        this.testRepository = testRepository;
        this.sentenceRepository = sentenceRepository;
        this.assignmentRepository = assignmentRepository;
        this.attemptRepository = attemptRepository;
        this.responseRepository = responseRepository;
        this.studentRepository = studentRepository;
        this.classroomRepository = classroomRepository;
        this.linkRepository = linkRepository;
        this.clock = clock;
    }

    // --- pruebas ---

    @Transactional
    public TestSummaryResponse createTest(UUID researcherId, CreateTestRequest request) {
        String code = request.code() == null ? "" : request.code().strip();
        if (!CODE.matcher(code).matches()) {
            throw new BusinessException("Test code must match ^[A-Z0-9-]{3,40}$");
        }
        if (testRepository.existsByCode(code)) {
            throw new ConflictException("Test code already exists");
        }
        String title = validTitle(request.title());
        SentenceTest test = new SentenceTest(code, title, researcherId);
        test.updateDetails(title, normalizeNotes(request.notes()));
        // saveAndFlush: el indice unico del codigo es el ultimo resguardo ante dos altas simultaneas.
        try {
            test = testRepository.saveAndFlush(test);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Test code already exists");
        }
        return toSummary(test, 0);
    }

    @Transactional(readOnly = true)
    public List<TestSummaryResponse> listTests() {
        return testRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(test -> toSummary(test, (int) sentenceRepository.countByTestId(test.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TestDetailResponse getTest(UUID testId) {
        return toDetail(requireTest(testId));
    }

    /** Solo en borrador: reemplaza todas las oraciones en el orden recibido (position = indice + 1). */
    @Transactional
    public TestDetailResponse updateTest(UUID testId, UpdateTestRequest request) {
        SentenceTest test = requireTest(testId);
        test.requireEditable();
        List<SentenceInput> inputs = request.sentences() == null ? List.of() : request.sentences();
        if (inputs.isEmpty() || inputs.size() > MAX_SENTENCES) {
            throw new BusinessException("A test needs between 1 and " + MAX_SENTENCES + " sentences");
        }
        List<TestSentence> sentences = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            sentences.add(toSentence(test, i + 1, inputs.get(i)));
        }
        test.updateDetails(validTitle(request.title()), normalizeNotes(request.notes()));
        // Borrar y vaciar antes de insertar: el indice unico (test_id, position) no admite solapes.
        sentenceRepository.deleteByTestId(testId);
        sentenceRepository.flush();
        sentenceRepository.saveAll(sentences);
        return toDetail(test);
    }

    @Transactional
    public TestDetailResponse activateTest(UUID testId) {
        SentenceTest test = requireTest(testId);
        test.activate((int) sentenceRepository.countByTestId(testId), clock.instant());
        return toDetail(test);
    }

    @Transactional
    public TestDetailResponse closeTest(UUID testId) {
        SentenceTest test = requireTest(testId);
        test.close(clock.instant());
        return toDetail(test);
    }

    // --- asignacion ---

    /** Idempotente: los alumnos ya asignados se ignoran. Devuelve el estado completo de la prueba. */
    @Transactional
    public List<AssignmentStatusResponse> assign(UUID researcherId, UUID testId, AssignTestRequest request) {
        SentenceTest test = requireTest(testId);
        boolean byClassroom = request.classroomId() != null;
        boolean byStudents = request.studentIds() != null && !request.studentIds().isEmpty();
        if (byClassroom == byStudents) {
            throw new BusinessException("Provide either classroomId or studentIds");
        }
        if (!test.isActive()) {
            throw new ConflictException("Test is not active");
        }
        List<Student> students = byClassroom ? classroomStudents(request.classroomId()) : students(request.studentIds());
        UUID classroomId = byClassroom ? request.classroomId() : null;
        for (Student student : students) {
            if (!assignmentRepository.existsByTestIdAndStudentId(testId, student.getId())) {
                assignmentRepository.save(new TestAssignment(test, student, classroomId, researcherId, clock.instant()));
            }
        }
        return assignments(testId);
    }

    @Transactional(readOnly = true)
    public List<AssignmentStatusResponse> assignments(UUID testId) {
        requireTest(testId);
        int sentenceCount = (int) sentenceRepository.countByTestId(testId);
        Map<UUID, TestAttempt> newestByStudent = new LinkedHashMap<>();
        // Orden ascendente por inicio: el ultimo que se guarda por alumno es el mas reciente.
        for (TestAttempt attempt : attemptRepository.findByTestIdOrderByStartedAtAsc(testId)) {
            newestByStudent.put(attempt.getStudent().getId(), attempt);
        }
        return assignmentRepository.findByTestIdOrderByAssignedAtAsc(testId).stream()
                .map(assignment -> toStatus(assignment, newestByStudent.get(assignment.getStudent().getId()), sentenceCount))
                .toList();
    }

    // --- intentos ---

    @Transactional(readOnly = true)
    public AttemptDetailResponse attemptDetail(UUID testId, UUID attemptId) {
        return toAttemptDetail(requireAttempt(testId, attemptId));
    }

    @Transactional
    public AttemptDetailResponse excludeAttempt(UUID researcherId, UUID testId, UUID attemptId, String reason) {
        TestAttempt attempt = requireAttempt(testId, attemptId);
        attempt.exclude(reason, researcherId, clock.instant());
        return toAttemptDetail(attempt);
    }

    /** Conteo humano de una oracion libre; solo sobre intentos completados. */
    @Transactional
    public AttemptDetailResponse.ResponseRow annotate(UUID researcherId, UUID responseId, int errorCount) {
        TestResponse response = responseRepository.findById(responseId)
                .orElseThrow(() -> new NotFoundException("Response not found"));
        if (response.getSentence().getKind() != SentenceKind.FREE) {
            throw new BusinessException("Annotation only applies to free sentences");
        }
        if (!response.getAttempt().isCompleted()) {
            throw new ConflictException("Attempt is not completed");
        }
        response.annotate(errorCount, researcherId, clock.instant());
        return toRow(response);
    }

    // --- cohorte (Task 6) ---

    @Transactional(readOnly = true)
    public Cohort loadCohort(UUID testId) {
        SentenceTest test = requireTest(testId);
        List<TestSentence> sentences = sentenceRepository.findByTestIdOrderByPositionAsc(testId);
        List<TestAttempt> completedAll = attemptRepository.findByTestIdOrderByStartedAtAsc(testId).stream()
                .filter(TestAttempt::isCompleted)
                .toList();
        List<TestAttempt> completedNotExcluded = completedAll.stream().filter(a -> !a.isExcluded()).toList();
        Map<UUID, List<TestResponse>> responsesByAttempt = new LinkedHashMap<>();
        Map<UUID, String> usernameByStudent = new LinkedHashMap<>();
        for (TestAttempt attempt : completedAll) {
            responsesByAttempt.put(attempt.getId(), new ArrayList<>());
            usernameByStudent.put(attempt.getStudent().getId(), attempt.getStudent().getUsername());
        }
        if (!completedAll.isEmpty()) {
            List<UUID> ids = completedAll.stream().map(TestAttempt::getId).toList();
            for (TestResponse response : responseRepository.findByAttemptIdInOrderByAttemptIdAscPositionAsc(ids)) {
                responsesByAttempt.get(response.getAttempt().getId()).add(response);
            }
        }
        return new Cohort(test, sentences, completedNotExcluded, responsesByAttempt, usernameByStudent, completedAll);
    }

    // --- helpers ---

    private SentenceTest requireTest(UUID testId) {
        return testRepository.findById(testId).orElseThrow(() -> new NotFoundException("Test not found"));
    }

    /** Un intento de otra prueba se reporta como inexistente para no filtrar su existencia. */
    private TestAttempt requireAttempt(UUID testId, UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .filter(attempt -> attempt.getTest().getId().equals(testId))
                .orElseThrow(() -> new NotFoundException("Attempt not found"));
    }

    private List<Student> classroomStudents(UUID classroomId) {
        if (!classroomRepository.existsById(classroomId)) {
            throw new NotFoundException("Classroom not found");
        }
        return linkRepository.findByClassroomIdAndDeletedAtIsNullOrderByCreatedAtAsc(classroomId).stream()
                .map(TeacherStudentLink::getStudent)
                .toList();
    }

    private List<Student> students(List<UUID> studentIds) {
        return studentIds.stream().distinct()
                .map(id -> studentRepository.findById(id).orElseThrow(() -> new NotFoundException("Student not found: " + id)))
                .toList();
    }

    private static String validTitle(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException("Title must have between 1 and " + MAX_TITLE_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String normalizeNotes(String notes) {
        if (notes == null) {
            return null;
        }
        String trimmed = notes.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static TestSentence toSentence(SentenceTest test, int position, SentenceInput input) {
        String text = input.referenceText() == null ? "" : input.referenceText().strip();
        if (text.isEmpty() || text.length() > MAX_REFERENCE_LENGTH) {
            throw new BusinessException("Sentence " + position + ": referenceText must have between 1 and "
                    + MAX_REFERENCE_LENGTH + " characters");
        }
        return new TestSentence(test, position,
                parseEnum(SentenceKind.class, input.kind(), "Sentence " + position + ": kind must be DICTATED or FREE"),
                text,
                parseEnum(Assistance.class, input.assistance(),
                        "Sentence " + position + ": assistance must be ASSISTED or UNASSISTED"));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(message);
        }
    }

    private TestSummaryResponse toSummary(SentenceTest test, int sentenceCount) {
        return new TestSummaryResponse(test.getId(), test.getCode(), test.getTitle(), test.getStatus().name(),
                sentenceCount,
                (int) assignmentRepository.countByTestId(test.getId()),
                (int) attemptRepository.countByTestIdAndStatus(test.getId(), AttemptStatus.COMPLETED),
                test.getCreatedAt());
    }

    private TestDetailResponse toDetail(SentenceTest test) {
        List<TestSentence> sentences = sentenceRepository.findByTestIdOrderByPositionAsc(test.getId());
        TestSummaryResponse summary = toSummary(test, sentences.size());
        int dictated = 0;
        int assisted = 0;
        for (TestSentence sentence : sentences) {
            if (sentence.getKind() == SentenceKind.DICTATED) {
                dictated++;
            }
            if (sentence.isAssisted()) {
                assisted++;
            }
        }
        return new TestDetailResponse(summary.id(), summary.code(), summary.title(), summary.status(),
                summary.sentenceCount(), summary.assignedCount(), summary.completedCount(), summary.createdAt(),
                test.getNotes(),
                sentences.stream()
                        .map(s -> new TestDetailResponse.SentenceEntry(s.getPosition(), s.getKind().name(),
                                s.getReferenceText(), s.getAssistance().name()))
                        .toList(),
                new TestDetailResponse.Counts(dictated, sentences.size() - dictated, assisted, sentences.size() - assisted));
    }

    private AssignmentStatusResponse toStatus(TestAssignment assignment, TestAttempt attempt, int sentenceCount) {
        Student student = assignment.getStudent();
        if (attempt == null) {
            return new AssignmentStatusResponse(student.getId(), student.getUsername(), assignment.getClassroomId(),
                    assignment.getAssignedAt(), null, PENDING, null, null, null, sentenceCount, false);
        }
        Integer currentPosition = attempt.isInProgress()
                ? (int) responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId()) + 1
                : null;
        return new AssignmentStatusResponse(student.getId(), student.getUsername(), assignment.getClassroomId(),
                assignment.getAssignedAt(), attempt.getId(), attempt.getStatus().name(), attempt.getStartedAt(),
                attempt.getCompletedAt(), currentPosition, sentenceCount,
                attempt.isExcluded());
    }

    private AttemptDetailResponse toAttemptDetail(TestAttempt attempt) {
        List<AttemptDetailResponse.ResponseRow> rows = responseRepository.findByAttemptIdOrderByPositionAsc(attempt.getId())
                .stream()
                .map(ResearchTestService::toRow)
                .toList();
        return new AttemptDetailResponse(attempt.getId(), attempt.getStudent().getUsername(), attempt.getStatus().name(),
                attempt.getStartedAt(), attempt.getCompletedAt(),
                attempt.getCancelReason() == null ? null : attempt.getCancelReason().name(),
                attempt.getAppVersion(), attempt.getBackendVersion(), attempt.getModelVersion(), attempt.getIncidentCount(),
                attempt.getExcludedAt(), attempt.getExclusionReason(), rows);
    }

    private static AttemptDetailResponse.ResponseRow toRow(TestResponse response) {
        TestSentence sentence = response.getSentence();
        boolean dictated = sentence.getKind() == SentenceKind.DICTATED;
        int wordCount = SentenceAligner.tokenize(dictated ? sentence.getReferenceText() : response.getFinalText()).size();
        String errorSource = dictated ? "AUTO" : response.getAnnotatedErrorCount() != null ? "ANNOTATED" : PENDING;
        return new AttemptDetailResponse.ResponseRow(response.getId(), response.getPosition(), sentence.getKind().name(),
                sentence.getAssistance().name(), sentence.getReferenceText(), response.getFinalText(), response.isSkipped(),
                wordCount, response.getAutoErrorCount(), response.getAutoErrorDetail(), response.getAnnotatedErrorCount(),
                response.effectiveErrorCount(), errorSource, response.getDurationFromFirstKeyMs(),
                response.getDurationFromStartMs(), response.getSuggestionsOffered(), response.getSuggestionsAccepted(),
                response.getSuggestionsRejected(), response.getSuggestionsUndone());
    }
}
