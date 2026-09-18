package com.mvp.backend.insights.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.insights.application.dto.LiveAttemptItem;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestResponseRepository;
import com.mvp.backend.sentencetest.domain.repository.TestSentenceRepository;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class LiveTestsServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-17T14:00:00Z");

    @Mock private ClassroomRepository classroomRepository;
    @Mock private TeacherStudentLinkRepository linkRepository;
    @Mock private PersonalDataCipher cipher;
    @Mock private TestAttemptRepository attemptRepository;
    @Mock private TestResponseRepository responseRepository;
    @Mock private TestSentenceRepository sentenceRepository;

    private LiveTestsService service;

    private final UUID teacherId = UUID.randomUUID();
    private final Teacher teacher = new Teacher("docente", "docente@colegio.edu.pe", null, "Colegio", "hash");
    private final Classroom classroomB = new Classroom(teacher, "3.º B");
    private final Classroom classroomC = new Classroom(teacher, "3.º C");
    private final Student ana = new Student("ana01", "Colegio", "hash");
    private final Student beto = new Student("beto02", "Colegio", "hash");
    private final SentenceTest test = new SentenceTest("PRUEBA-01", "Dictado", UUID.randomUUID());

    @BeforeEach
    void setUp() {
        TeacherAccess access = new TeacherAccess(classroomRepository, linkRepository, cipher);
        service = new LiveTestsService(access, attemptRepository, responseRepository, sentenceRepository);
        lenient().when(cipher.decrypt(anyString())).thenAnswer(inv -> "Nombre " + inv.getArgument(0));
    }

    @Test
    void listsInProgressAttemptsOfLinkedStudentsWithCurrentPosition() {
        when(linkRepository.findActiveWithStudentAndClassroomByTeacherId(teacherId)).thenReturn(List.of(
                new TeacherStudentLink(teacher, ana, classroomB, "enc-ana01", null),
                new TeacherStudentLink(teacher, beto, classroomC, "enc-beto02", null)));
        TestAttempt anaAttempt = new TestAttempt(test, ana, "app-1", "backend-1", NOW.minusSeconds(120));
        TestAttempt betoAttempt = new TestAttempt(test, beto, "app-1", "backend-1", NOW.minusSeconds(30));
        when(attemptRepository.findByStudentIdInAndStatus(List.of(ana.getId(), beto.getId()), AttemptStatus.IN_PROGRESS))
                .thenReturn(List.of(betoAttempt, anaAttempt));
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(anaAttempt.getId())).thenReturn(2L);
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(betoAttempt.getId())).thenReturn(0L);
        when(sentenceRepository.countByTestId(test.getId())).thenReturn(5L);

        List<LiveAttemptItem> items = service.live(teacherId);

        // Quien empezo antes va primero.
        assertThat(items).extracting(LiveAttemptItem::attemptId).containsExactly(anaAttempt.getId(), betoAttempt.getId());
        LiveAttemptItem first = items.get(0);
        assertThat(first.studentId()).isEqualTo(ana.getId());
        assertThat(first.username()).isEqualTo("ana01");
        assertThat(first.realName()).isEqualTo("Nombre enc-ana01");
        assertThat(first.classroomId()).isEqualTo(classroomB.getId());
        assertThat(first.classroomName()).isEqualTo("3.º B");
        assertThat(first.testCode()).isEqualTo("PRUEBA-01");
        assertThat(first.testTitle()).isEqualTo("Dictado");
        // Posicion en curso = oraciones terminadas + 1.
        assertThat(first.currentPosition()).isEqualTo(3);
        assertThat(first.sentenceCount()).isEqualTo(5);
        assertThat(first.startedAt()).isEqualTo(NOW.minusSeconds(120));

        LiveAttemptItem second = items.get(1);
        assertThat(second.classroomName()).isEqualTo("3.º C");
        assertThat(second.currentPosition()).isEqualTo(1);
    }

    @Test
    void currentPositionNeverExceedsSentenceCount() {
        when(linkRepository.findActiveWithStudentAndClassroomByTeacherId(teacherId)).thenReturn(List.of(
                new TeacherStudentLink(teacher, ana, classroomB, "enc-ana01", null)));
        TestAttempt attempt = new TestAttempt(test, ana, "app-1", "backend-1", NOW.minusSeconds(120));
        when(attemptRepository.findByStudentIdInAndStatus(List.of(ana.getId()), AttemptStatus.IN_PROGRESS))
                .thenReturn(List.of(attempt));
        // Ultima oracion terminada pero el intento aun no se marco COMPLETED.
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId())).thenReturn(5L);
        when(sentenceRepository.countByTestId(test.getId())).thenReturn(5L);

        assertThat(service.live(teacherId).get(0).currentPosition()).isEqualTo(5);
    }

    @Test
    void teacherWithoutLinkedStudentsGetsEmptyListWithoutQueryingAttempts() {
        when(linkRepository.findActiveWithStudentAndClassroomByTeacherId(teacherId)).thenReturn(List.of());

        assertThat(service.live(teacherId)).isEmpty();
        verify(attemptRepository, never()).findByStudentIdInAndStatus(any(), any());
    }
}
