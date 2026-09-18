package com.mvp.backend.insights.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.mvp.backend.insights.application.dto.ClassroomActivityResponse;
import com.mvp.backend.insights.application.dto.ClassroomErrorsResponse;
import com.mvp.backend.insights.application.dto.ErrorTypeBreakdown;
import com.mvp.backend.insights.application.dto.RecentCorrectionItem;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
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
class ClassroomInsightsServiceTests {

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

    private ClassroomInsightsService service;

    private final UUID teacherId = UUID.randomUUID();
    private final Teacher teacher = new Teacher("docente", "docente@colegio.edu.pe", null, "Colegio", "hash");
    private final Classroom classroom = new Classroom(teacher, "3.º B");
    private final Student ana = new Student("ana01", "Colegio", "hash");
    private final Student beto = new Student("beto02", "Colegio", "hash");
    private final Student carla = new Student("carla03", "Colegio", "hash");
    private final Student dani = new Student("dani04", "Colegio", "hash");
    private final List<TeacherStudentLink> links = List.of(link(ana), link(beto), link(carla), link(dani));
    private final List<UUID> studentIds = List.of(ana.getId(), beto.getId(), carla.getId(), dani.getId());

    @BeforeEach
    void setUp() {
        TeacherAccess access = new TeacherAccess(classroomRepository, linkRepository, cipher);
        service = new ClassroomInsightsService(access, sessionRepository, wordCorrectionRepository, clock);
        lenient().when(cipher.decrypt(anyString())).thenAnswer(inv -> "Nombre " + inv.getArgument(0));
    }

    private void ownClassroomWithLinks() {
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));
        when(linkRepository.findActiveWithStudentByClassroomId(classroom.getId())).thenReturn(links);
    }

    @Test
    void activityOrdersByCorrectionsThenLastActivityAndCountsOutcomes() {
        ownClassroomWithLinks();
        Instant t1 = START.plusSeconds(3600);
        Instant t2 = START.plusSeconds(7200);
        Instant afterPeriod = END.plusSeconds(3600);
        List<CorrectionSession> sessions = List.of(
                session(ana, "ola", t2, s -> s.registerFeedback("hola", "hola amigo", true, 1, null)),
                session(ana, "baca", t1, s -> s.registerFeedback("vaca", null, false, 1, "UNDO")),
                session(beto, "avion", t2, s -> { }),
                session(dani, "camion", t1, s -> s.registerFeedback("camión", null, true, 1, null)));
        when(sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(studentIds), eq(START), eq(END), any(Pageable.class))).thenReturn(sessions);
        when(sessionRepository.lastActivityByStudent(studentIds)).thenReturn(List.of(
                new Object[] {ana.getId(), afterPeriod},
                new Object[] {beto.getId(), t2},
                new Object[] {dani.getId(), t1}));

        ClassroomActivityResponse response = service.activity(teacherId, classroom.getId(), DAY, DAY);

        assertThat(response.classroomId()).isEqualTo(classroom.getId());
        assertThat(response.classroomName()).isEqualTo("3.º B");
        assertThat(response.from()).isEqualTo(DAY);
        assertThat(response.to()).isEqualTo(DAY);
        assertThat(response.students()).extracting(ClassroomActivityResponse.StudentActivity::username)
                .containsExactly("ana01", "beto02", "dani04", "carla03");

        var first = response.students().get(0);
        assertThat(first.studentId()).isEqualTo(ana.getId());
        assertThat(first.realName()).isEqualTo("Nombre enc-ana01");
        assertThat(first.correctionsInPeriod()).isEqualTo(2);
        // La ultima actividad se toma sin limite de periodo.
        assertThat(first.lastActivityAt()).isEqualTo(afterPeriod);
        assertThat(first.outcomes()).isEqualTo(new ClassroomActivityResponse.OutcomeCounts(1, 0, 0, 1, 0));

        assertThat(response.students().get(1).outcomes())
                .isEqualTo(new ClassroomActivityResponse.OutcomeCounts(0, 0, 0, 0, 1));
        assertThat(response.students().get(2).outcomes())
                .isEqualTo(new ClassroomActivityResponse.OutcomeCounts(0, 1, 0, 0, 0));

        var last = response.students().get(3);
        assertThat(last.correctionsInPeriod()).isZero();
        assertThat(last.lastActivityAt()).isNull();
        assertThat(last.outcomes()).isEqualTo(new ClassroomActivityResponse.OutcomeCounts(0, 0, 0, 0, 0));
    }

    @Test
    void recentMapsOutcomeFinalTextAndTestContext() {
        ownClassroomWithLinks();
        Instant t1 = START.plusSeconds(60);
        TestResponse testResponse = testResponse(Assistance.ASSISTED);
        List<CorrectionSession> sessions = List.of(
                session(ana, "ola", t1.plusSeconds(3), s -> s.registerFeedback("hola", "hola amigo", true, 1, null)),
                session(beto, "caza", t1.plusSeconds(2), s -> s.registerFeedback("casa", null, true, 1, null)),
                session(ana, "baca", t1.plusSeconds(1), testResponse, s -> s.registerFeedback("vaca", null, false, 1, "WRONG")),
                session(dani, "avion", t1, s -> { }));
        when(sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(studentIds), eq(START), eq(END), any(Pageable.class))).thenReturn(sessions);

        List<RecentCorrectionItem> items = service.recentCorrections(teacherId, classroom.getId(), DAY, DAY, null);

        assertThat(items).hasSize(4);
        RecentCorrectionItem edited = items.get(0);
        assertThat(edited.sessionId()).isEqualTo(sessions.get(0).getId());
        assertThat(edited.studentId()).isEqualTo(ana.getId());
        assertThat(edited.username()).isEqualTo("ana01");
        assertThat(edited.realName()).isEqualTo("Nombre enc-ana01");
        assertThat(edited.createdAt()).isEqualTo(t1.plusSeconds(3));
        assertThat(edited.originalText()).isEqualTo("ola");
        assertThat(edited.correctedText()).isEqualTo("hola corregido");
        assertThat(edited.finalText()).isEqualTo("hola amigo");
        assertThat(edited.outcome()).isEqualTo("EDITED");
        assertThat(edited.outcomeLabel()).isEqualTo("Resolvió solo");
        assertThat(edited.inTest()).isFalse();
        assertThat(edited.assistance()).isNull();

        RecentCorrectionItem accepted = items.get(1);
        assertThat(accepted.finalText()).isEqualTo("casa");
        assertThat(accepted.outcome()).isEqualTo("ACCEPTED");

        RecentCorrectionItem rejectedInTest = items.get(2);
        assertThat(rejectedInTest.finalText()).isEqualTo("baca");
        assertThat(rejectedInTest.outcome()).isEqualTo("REJECTED");
        assertThat(rejectedInTest.inTest()).isTrue();
        assertThat(rejectedInTest.assistance()).isEqualTo("ASSISTED");

        RecentCorrectionItem unanswered = items.get(3);
        assertThat(unanswered.finalText()).isEqualTo("avion");
        assertThat(unanswered.outcome()).isEqualTo("UNANSWERED");
        assertThat(unanswered.outcomeLabel()).isEqualTo("Sin respuesta");
    }

    @Test
    void recentClampsLimitBetweenOneAndTwoHundredDefaultingToFifty() {
        ownClassroomWithLinks();
        when(sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(studentIds), eq(START), eq(END), any(Pageable.class))).thenReturn(List.of());

        service.recentCorrections(teacherId, classroom.getId(), DAY, DAY, null);
        service.recentCorrections(teacherId, classroom.getId(), DAY, DAY, 0);
        service.recentCorrections(teacherId, classroom.getId(), DAY, DAY, 500);
        service.recentCorrections(teacherId, classroom.getId(), DAY, DAY, 7);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository, times(4))
                .findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        eq(studentIds), eq(START), eq(END), pageable.capture());
        assertThat(pageable.getAllValues()).extracting(Pageable::getPageSize).containsExactly(50, 1, 200, 7);
        assertThat(pageable.getAllValues()).extracting(Pageable::getPageNumber).containsOnly(0);
    }

    @Test
    void errorsGroupsByTypeWithTopFiveWordPairs() {
        ownClassroomWithLinks();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new Object[] {ana.getId(), "camion", "camión"});
        }
        for (String[] pair : new String[][] {{"papa", "papá"}, {"mama", "mamá"}, {"arbol", "árbol"},
                {"avion", "avión"}, {"cancion", "canción"}, {"lapiz", "lápiz"}}) {
            rows.add(new Object[] {beto.getId(), pair[0], pair[1]});
        }
        rows.add(new Object[] {ana.getId(), "baca", "vaca"});
        rows.add(new Object[] {dani.getId(), "baca", "vaca"});
        rows.add(new Object[] {dani.getId(), "ablar", "hablar"});
        when(wordCorrectionRepository.wordPairsForStudents(studentIds, START, END)).thenReturn(rows);

        ClassroomErrorsResponse response = service.errors(teacherId, classroom.getId(), DAY, DAY);

        assertThat(response.classroomId()).isEqualTo(classroom.getId());
        assertThat(response.from()).isEqualTo(DAY);
        assertThat(response.total()).isEqualTo(12);
        assertThat(response.types()).extracting(ErrorTypeBreakdown::type)
                .containsExactly("TILDE", "CONFUSION_B_V", "H_MUDA");
        assertThat(response.types()).extracting(ErrorTypeBreakdown::count).containsExactly(9L, 2L, 1L);
        assertThat(response.types().get(0).label()).isEqualTo("Tildes y acentuación");

        List<ErrorTypeBreakdown.WordPair> tilde = response.types().get(0).topWords();
        assertThat(tilde).hasSize(5);
        assertThat(tilde.get(0)).isEqualTo(new ErrorTypeBreakdown.WordPair("camion", "camión", 3));
        // Empates por cantidad: orden alfabetico de la palabra original.
        assertThat(tilde).extracting(ErrorTypeBreakdown.WordPair::original)
                .containsExactly("camion", "arbol", "avion", "cancion", "lapiz");
        assertThat(response.types().get(1).topWords())
                .containsExactly(new ErrorTypeBreakdown.WordPair("baca", "vaca", 2));
        assertThat(response.types().get(2).topWords())
                .containsExactly(new ErrorTypeBreakdown.WordPair("ablar", "hablar", 1));
    }

    @Test
    void classroomWithoutStudentsReturnsEmptyResultsWithoutQuerying() {
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));
        when(linkRepository.findActiveWithStudentByClassroomId(classroom.getId())).thenReturn(List.of());

        assertThat(service.activity(teacherId, classroom.getId(), DAY, DAY).students()).isEmpty();
        assertThat(service.recentCorrections(teacherId, classroom.getId(), DAY, DAY, 10)).isEmpty();
        ClassroomErrorsResponse errors = service.errors(teacherId, classroom.getId(), DAY, DAY);
        assertThat(errors.total()).isZero();
        assertThat(errors.types()).isEmpty();
    }

    @Test
    void anotherTeachersClassroomIsForbidden() {
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activity(teacherId, classroom.getId(), DAY, DAY))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.recentCorrections(teacherId, classroom.getId(), DAY, DAY, 10))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.errors(teacherId, classroom.getId(), DAY, DAY))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void invalidPeriodIsRejected() {
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));

        assertThatThrownBy(() -> service.activity(teacherId, classroom.getId(), "2026-09-10", "2026-09-01"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.recentCorrections(teacherId, classroom.getId(), "2026-09-10", "2026-09-01", 10))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.errors(teacherId, classroom.getId(), "hoy", null))
                .isInstanceOf(BusinessException.class);
    }

    private TeacherStudentLink link(Student student) {
        return new TeacherStudentLink(teacher, student, classroom, "enc-" + student.getUsername(), null);
    }

    private CorrectionSession session(Student student, String original, Instant createdAt,
            Consumer<CorrectionSession> feedback) {
        return session(student, original, createdAt, null, feedback);
    }

    private CorrectionSession session(Student student, String original, Instant createdAt, TestResponse testResponse,
            Consumer<CorrectionSession> feedback) {
        CorrectionSession session = new CorrectionSession(student, original, testResponse);
        session.complete("hola corregido", 1, "[]", 10L);
        ReflectionTestUtils.setField(session, "createdAt", createdAt);
        feedback.accept(session);
        return session;
    }

    private TestResponse testResponse(Assistance assistance) {
        SentenceTest test = new SentenceTest("PRUEBA-01", "Dictado", UUID.randomUUID());
        TestAttempt attempt = new TestAttempt(test, ana, "app-1.0", "backend-1.0", START);
        TestSentence sentence = new TestSentence(test, 1, SentenceKind.DICTATED, "La vaca come.", assistance);
        return new TestResponse(attempt, sentence, START);
    }
}
