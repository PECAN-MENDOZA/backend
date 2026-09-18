package com.mvp.backend.insights.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.insights.application.dto.ErrorTypeBreakdown.WordPair;
import com.mvp.backend.insights.application.dto.StudentErrorsResponse;
import com.mvp.backend.insights.application.dto.StudentHelpResponse;
import com.mvp.backend.insights.application.dto.StudentTestSummary;
import com.mvp.backend.insights.application.dto.StudentWritingItem;
import com.mvp.backend.sentencetest.application.service.SentenceAligner;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestResponseRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class StudentInsightsServiceTests {

    // 2026-09-10 en Lima (UTC-5): [2026-09-10T05:00Z, 2026-09-11T05:00Z).
    private static final Instant START = Instant.parse("2026-09-10T05:00:00Z");
    private static final Instant END = Instant.parse("2026-09-11T05:00:00Z");
    private static final String DAY = "2026-09-10";

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);

    @Mock private ClassroomRepository classroomRepository;
    @Mock private TeacherStudentLinkRepository linkRepository;
    @Mock private PersonalDataCipher cipher;
    @Mock private CorrectionSessionRepository sessionRepository;
    @Mock private WordCorrectionRepository wordCorrectionRepository;
    @Mock private TestAttemptRepository attemptRepository;
    @Mock private TestResponseRepository responseRepository;

    private StudentInsightsService service;

    private final UUID teacherId = UUID.randomUUID();
    private final Teacher teacher = new Teacher("docente", "docente@colegio.edu.pe", null, "Colegio", "hash");
    private final Classroom classroom = new Classroom(teacher, "3.º B");
    private final Student ana = new Student("ana01", "Colegio", "hash");
    private final TeacherStudentLink link = new TeacherStudentLink(teacher, ana, classroom, "enc-ana01", null);
    private final List<UUID> onlyAna = List.of(ana.getId());

    @BeforeEach
    void setUp() {
        TeacherAccess access = new TeacherAccess(classroomRepository, linkRepository, cipher);
        service = new StudentInsightsService(
                access, sessionRepository, wordCorrectionRepository, attemptRepository, responseRepository, clock);
        lenient().when(cipher.decrypt(anyString())).thenAnswer(inv -> "Nombre " + inv.getArgument(0));
    }

    private void ownLink() {
        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, ana.getId()))
                .thenReturn(Optional.of(link));
    }

    @Test
    void errorsGroupsByTypeWithExamplesAndRepeatedPracticeWords() {
        ownLink();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new Object[] {ana.getId(), "camion", "camión"});
        }
        for (String[] pair : new String[][] {{"papa", "papá"}, {"papa", "papá"}, {"mama", "mamá"}, {"arbol", "árbol"},
                {"avion", "avión"}, {"cancion", "canción"}, {"lapiz", "lápiz"}}) {
            rows.add(new Object[] {ana.getId(), pair[0], pair[1]});
        }
        rows.add(new Object[] {ana.getId(), "baca", "vaca"});
        rows.add(new Object[] {ana.getId(), "baca", "vaca"});
        rows.add(new Object[] {ana.getId(), "ablar", "hablar"});
        when(wordCorrectionRepository.wordPairsForStudents(onlyAna, START, END)).thenReturn(rows);

        StudentErrorsResponse response = service.errors(teacherId, ana.getId(), DAY, DAY);

        assertThat(response.studentId()).isEqualTo(ana.getId());
        assertThat(response.from()).isEqualTo(DAY);
        assertThat(response.to()).isEqualTo(DAY);
        assertThat(response.total()).isEqualTo(13);
        assertThat(response.types()).extracting(StudentErrorsResponse.ErrorTypeExamples::type)
                .containsExactly("TILDE", "CONFUSION_B_V", "H_MUDA");
        assertThat(response.types()).extracting(StudentErrorsResponse.ErrorTypeExamples::count)
                .containsExactly(10L, 2L, 1L);
        assertThat(response.types().get(0).label()).isEqualTo("Tildes y acentuación");

        // Maximo 5 ejemplos por tipo: cuenta desc y luego palabra original.
        List<WordPair> tilde = response.types().get(0).examples();
        assertThat(tilde).hasSize(5);
        assertThat(tilde).extracting(WordPair::original).containsExactly("camion", "papa", "arbol", "avion", "cancion");
        assertThat(tilde.get(0)).isEqualTo(new WordPair("camion", "camión", 3));
        assertThat(response.types().get(2).examples()).containsExactly(new WordPair("ablar", "hablar", 1));

        // Palabras para practicar: solo pares con al menos 2 repeticiones, cuenta desc, sin importar el tipo.
        assertThat(response.practiceWords()).containsExactly(
                new WordPair("camion", "camión", 3),
                new WordPair("baca", "vaca", 2),
                new WordPair("papa", "papá", 2));
    }

    @Test
    void practiceWordsAreCappedAtTwenty() {
        ownLink();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String original = "palabra" + String.format("%02d", i);
            rows.add(new Object[] {ana.getId(), original, original + "x"});
            rows.add(new Object[] {ana.getId(), original, original + "x"});
        }
        when(wordCorrectionRepository.wordPairsForStudents(onlyAna, START, END)).thenReturn(rows);

        StudentErrorsResponse response = service.errors(teacherId, ana.getId(), DAY, DAY);

        assertThat(response.total()).isEqualTo(50);
        assertThat(response.practiceWords()).hasSize(20);
        assertThat(response.practiceWords()).allSatisfy(pair -> assertThat(pair.count()).isEqualTo(2));
    }

    @Test
    void helpCountsOutcomesWithPercentagesOverTotal() {
        ownLink();
        Instant t = START.plusSeconds(60);
        List<CorrectionSession> sessions = List.of(
                session("ola", t, s -> s.registerFeedback("hola", "hola amigo", true, 1, null)),
                session("baca", t, s -> s.registerFeedback("vaca", "vaca lechera", true, 1, null)),
                session("camion", t, s -> s.registerFeedback("camión", null, true, 1, null)),
                session("avion", t, s -> s.registerFeedback("avión", null, false, 1, "WRONG")),
                session("arbol", t, s -> { }));
        when(sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(onlyAna), eq(START), eq(END), any(Pageable.class))).thenReturn(sessions);

        StudentHelpResponse help = service.help(teacherId, ana.getId(), DAY, DAY);

        assertThat(help.studentId()).isEqualTo(ana.getId());
        assertThat(help.from()).isEqualTo(DAY);
        assertThat(help.total()).isEqualTo(5);
        assertThat(help.edited()).isEqualTo(2);
        assertThat(help.accepted()).isEqualTo(1);
        assertThat(help.rejected()).isEqualTo(1);
        assertThat(help.undone()).isZero();
        assertThat(help.unanswered()).isEqualTo(1);
        assertThat(help.editedPct()).isEqualTo(40.0);
        assertThat(help.acceptedPct()).isEqualTo(20.0);
        assertThat(help.rejectedPct()).isEqualTo(20.0);
        assertThat(help.undonePct()).isZero();
        assertThat(help.unansweredPct()).isEqualTo(20.0);
    }

    @Test
    void helpWithoutSessionsIsAllZeros() {
        ownLink();
        when(sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(onlyAna), eq(START), eq(END), any(Pageable.class))).thenReturn(List.of());

        StudentHelpResponse help = service.help(teacherId, ana.getId(), DAY, DAY);

        assertThat(help).isEqualTo(new StudentHelpResponse(ana.getId(), DAY, DAY, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void writingsMapOutcomeFinalTextAndTestContext() {
        ownLink();
        Instant t1 = START.plusSeconds(60);
        SentenceTest test = new SentenceTest("PRUEBA-01", "Dictado", UUID.randomUUID());
        TestResponse testResponse = testResponse(test, 1, SentenceKind.DICTATED, "La vaca come.", Assistance.ASSISTED);
        List<CorrectionSession> sessions = List.of(
                session("ola", t1.plusSeconds(2), s -> s.registerFeedback("hola", "hola amigo", true, 1, null)),
                session("baca", t1.plusSeconds(1), testResponse, s -> s.registerFeedback("vaca", null, false, 1, "UNDO")),
                session("avion", t1, s -> { }));
        when(sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(onlyAna), eq(START), eq(END), any(Pageable.class))).thenReturn(sessions);

        List<StudentWritingItem> items = service.writings(teacherId, ana.getId(), DAY, DAY, null);

        assertThat(items).hasSize(3);
        StudentWritingItem edited = items.get(0);
        assertThat(edited.sessionId()).isEqualTo(sessions.get(0).getId());
        assertThat(edited.createdAt()).isEqualTo(t1.plusSeconds(2));
        assertThat(edited.originalText()).isEqualTo("ola");
        assertThat(edited.finalText()).isEqualTo("hola amigo");
        assertThat(edited.outcome()).isEqualTo("EDITED");
        assertThat(edited.outcomeLabel()).isEqualTo("Resolvió solo");
        assertThat(edited.inTest()).isFalse();
        assertThat(edited.assistance()).isNull();
        assertThat(edited.testCode()).isNull();

        StudentWritingItem undoneInTest = items.get(1);
        assertThat(undoneInTest.finalText()).isEqualTo("baca");
        assertThat(undoneInTest.outcome()).isEqualTo("UNDONE");
        assertThat(undoneInTest.inTest()).isTrue();
        assertThat(undoneInTest.assistance()).isEqualTo("ASSISTED");
        assertThat(undoneInTest.testCode()).isEqualTo("PRUEBA-01");

        assertThat(items.get(2).outcome()).isEqualTo("UNANSWERED");
        assertThat(items.get(2).finalText()).isEqualTo("avion");
    }

    @Test
    void writingsClampLimitBetweenOneAndTwoHundredDefaultingToOneHundred() {
        ownLink();
        when(sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(onlyAna), eq(START), eq(END), any(Pageable.class))).thenReturn(List.of());

        service.writings(teacherId, ana.getId(), DAY, DAY, null);
        service.writings(teacherId, ana.getId(), DAY, DAY, 0);
        service.writings(teacherId, ana.getId(), DAY, DAY, 500);
        service.writings(teacherId, ana.getId(), DAY, DAY, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository, times(4))
                .findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        eq(onlyAna), eq(START), eq(END), pageable.capture());
        assertThat(pageable.getAllValues()).extracting(Pageable::getPageSize).containsExactly(100, 1, 200, 20);
    }

    @Test
    void testsListOnlyCompletedAttemptsWithParsedEditsAndErrorSource() {
        ownLink();
        SentenceTest test = new SentenceTest("PRUEBA-01", "Dictado y libre", UUID.randomUUID());
        TestAttempt done = new TestAttempt(test, ana, "app-1", "backend-1", START);
        done.complete(START.plusSeconds(600));
        TestAttempt excluded = new TestAttempt(test, ana, "app-1", "backend-1", START.plusSeconds(3600));
        excluded.complete(START.plusSeconds(4200));
        excluded.exclude("Se desconecto el tablet a mitad", UUID.randomUUID(), START.plusSeconds(5000));
        when(attemptRepository.findByStudentIdAndStatusOrderByCompletedAtDesc(ana.getId(), AttemptStatus.COMPLETED))
                .thenReturn(List.of(excluded, done));

        TestResponse dictated = new TestResponse(done,
                new TestSentence(test, 1, SentenceKind.DICTATED, "La vaca come hierba.", Assistance.ASSISTED), START);
        dictated.finish("La baca come ierba", 500L, 4000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(),
                START.plusSeconds(4));
        SentenceAligner.Alignment alignment = SentenceAligner.align("La vaca come hierba.", "La baca come ierba");
        dictated.recordAutoErrors(alignment.errorCount(), alignment.toJson());
        TestResponse free = new TestResponse(done,
                new TestSentence(test, 2, SentenceKind.FREE, "Escribe sobre tu mascota", Assistance.UNASSISTED),
                START.plusSeconds(10));
        free.finish("Mi gato duerme", 300L, 6000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(),
                START.plusSeconds(16));
        TestResponse skipped = new TestResponse(excluded,
                new TestSentence(test, 3, SentenceKind.FREE, "Escribe sobre tu comida", Assistance.UNASSISTED),
                START.plusSeconds(3600));
        skipped.finish("", null, 500L, true, TestResponse.Counters.ZERO, UUID.randomUUID(), START.plusSeconds(3601));
        when(responseRepository.findWithSentenceByAttemptIdIn(List.of(excluded.getId(), done.getId())))
                .thenReturn(List.of(skipped, dictated, free));

        List<StudentTestSummary> summaries = service.tests(teacherId, ana.getId());

        assertThat(summaries).hasSize(2);
        StudentTestSummary first = summaries.get(0);
        assertThat(first.attemptId()).isEqualTo(excluded.getId());
        assertThat(first.excluded()).isTrue();
        assertThat(first.completedAt()).isEqualTo(START.plusSeconds(4200));
        assertThat(first.sentences()).hasSize(1);
        StudentTestSummary.SentenceSummary skippedSummary = first.sentences().get(0);
        assertThat(skippedSummary.skipped()).isTrue();
        assertThat(skippedSummary.finalText()).isEmpty();
        assertThat(skippedSummary.errorCount()).isNull();
        assertThat(skippedSummary.errorSource()).isEqualTo("PENDING");
        assertThat(skippedSummary.durationFromFirstKeyMs()).isNull();

        StudentTestSummary second = summaries.get(1);
        assertThat(second.attemptId()).isEqualTo(done.getId());
        assertThat(second.testCode()).isEqualTo("PRUEBA-01");
        assertThat(second.testTitle()).isEqualTo("Dictado y libre");
        assertThat(second.excluded()).isFalse();
        assertThat(second.sentences()).extracting(StudentTestSummary.SentenceSummary::position).containsExactly(1, 2);

        StudentTestSummary.SentenceSummary dictatedSummary = second.sentences().get(0);
        assertThat(dictatedSummary.kind()).isEqualTo("DICTATED");
        assertThat(dictatedSummary.assistance()).isEqualTo("ASSISTED");
        assertThat(dictatedSummary.referenceText()).isEqualTo("La vaca come hierba.");
        assertThat(dictatedSummary.finalText()).isEqualTo("La baca come ierba");
        assertThat(dictatedSummary.errorCount()).isEqualTo(2);
        assertThat(dictatedSummary.errorSource()).isEqualTo("AUTO");
        assertThat(dictatedSummary.durationFromFirstKeyMs()).isEqualTo(3500L);
        assertThat(dictatedSummary.edits()).containsExactly(
                new StudentTestSummary.Edit("SUSTITUCION", "vaca", "baca"),
                new StudentTestSummary.Edit("SUSTITUCION", "hierba", "ierba"));

        StudentTestSummary.SentenceSummary freeSummary = second.sentences().get(1);
        assertThat(freeSummary.kind()).isEqualTo("FREE");
        assertThat(freeSummary.errorCount()).isNull();
        assertThat(freeSummary.errorSource()).isEqualTo("PENDING");
        assertThat(freeSummary.edits()).isEmpty();
        assertThat(freeSummary.durationFromFirstKeyMs()).isEqualTo(5700L);
    }

    @Test
    void testsWithoutCompletedAttemptsDoesNotLoadResponses() {
        ownLink();
        when(attemptRepository.findByStudentIdAndStatusOrderByCompletedAtDesc(ana.getId(), AttemptStatus.COMPLETED))
                .thenReturn(List.of());

        assertThat(service.tests(teacherId, ana.getId())).isEmpty();
        verify(responseRepository, never()).findWithSentenceByAttemptIdIn(any());
    }

    @Test
    void studentWithoutActiveLinkIsForbidden() {
        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, ana.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.errors(teacherId, ana.getId(), DAY, DAY)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.help(teacherId, ana.getId(), DAY, DAY)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.writings(teacherId, ana.getId(), DAY, DAY, 10))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.tests(teacherId, ana.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void invalidPeriodIsRejected() {
        ownLink();

        assertThatThrownBy(() -> service.errors(teacherId, ana.getId(), "2026-09-10", "2026-09-01"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.help(teacherId, ana.getId(), "hoy", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.writings(teacherId, ana.getId(), "2026-01-01", "2026-09-01", 10))
                .isInstanceOf(BusinessException.class);
    }

    private CorrectionSession session(String original, Instant createdAt, Consumer<CorrectionSession> feedback) {
        return session(original, createdAt, null, feedback);
    }

    private CorrectionSession session(String original, Instant createdAt, TestResponse testResponse,
            Consumer<CorrectionSession> feedback) {
        CorrectionSession session = new CorrectionSession(ana, original, testResponse);
        session.complete("corregido", 1, "[]", 10L);
        ReflectionTestUtils.setField(session, "createdAt", createdAt);
        feedback.accept(session);
        return session;
    }

    private TestResponse testResponse(SentenceTest test, int position, SentenceKind kind, String reference,
            Assistance assistance) {
        TestAttempt attempt = new TestAttempt(test, ana, "app-1.0", "backend-1.0", START);
        TestSentence sentence = new TestSentence(test, position, kind, reference, assistance);
        return new TestResponse(attempt, sentence, START);
    }
}
