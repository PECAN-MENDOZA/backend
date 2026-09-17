package com.mvp.backend.sentencetest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AttemptCancelReason;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.model.TestStatus;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.student.domain.model.Student;

class SentenceTestDomainTests {

    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");

    @Test
    void draftTestActivatesOnlyWithSentencesAndBecomesImmutable() {
        SentenceTest test = new SentenceTest("PRUEBA-01", "Dictado 1", UUID.randomUUID());
        assertThat(test.getStatus()).isEqualTo(TestStatus.DRAFT);
        assertThatThrownBy(() -> test.activate(0, now)).isInstanceOf(BusinessException.class);
        test.activate(3, now);
        assertThat(test.getStatus()).isEqualTo(TestStatus.ACTIVE);
        assertThat(test.getActivatedAt()).isEqualTo(now);
        assertThatThrownBy(() -> test.requireEditable()).isInstanceOf(ConflictException.class);
        test.close(now.plusSeconds(60));
        assertThat(test.getStatus()).isEqualTo(TestStatus.CLOSED);
        assertThatThrownBy(() -> test.activate(3, now)).isInstanceOf(ConflictException.class);
    }

    @Test
    void attemptCompletesCancelsAndExcludesWithoutDeleting() {
        TestAttempt attempt = attempt();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(attempt.recordModelVersion("m@1")).isTrue();
        assertThat(attempt.recordModelVersion("m@1")).isTrue();
        assertThat(attempt.recordModelVersion("m@2")).isFalse();
        assertThat(attempt.getModelVersion()).isEqualTo("m@1");
        attempt.complete(now);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.COMPLETED);
        assertThatThrownBy(() -> attempt.cancel(AttemptCancelReason.ABANDONED, now)).isInstanceOf(ConflictException.class);
        UUID researcher = UUID.randomUUID();
        attempt.exclude("Se interrumpio la sesion por simulacro", researcher, now);
        assertThat(attempt.isExcluded()).isTrue();
        assertThat(attempt.getExcludedBy()).isEqualTo(researcher);
        assertThatThrownBy(() -> attempt.exclude("corto", researcher, now)).isInstanceOf(BusinessException.class);
    }

    @Test
    void responseFinishComputesDurationsAndRejectsInconsistentOffsets() {
        TestAttempt attempt = attempt();
        TestSentence sentence = new TestSentence(attempt.getTest(), 1, SentenceKind.DICTATED, "El perro corre.", Assistance.ASSISTED);
        TestResponse response = new TestResponse(attempt, sentence, now);
        assertThat(response.isStarted()).isTrue();
        assertThat(response.isFinished()).isFalse();
        response.finish("El pero corre.", 1500L, 9000L, false, new TestResponse.Counters(2, 1, 1, 0), UUID.randomUUID(), now.plusMillis(9000));
        assertThat(response.getDurationFromFirstKeyMs()).isEqualTo(7500L);
        assertThat(response.getDurationFromStartMs()).isEqualTo(9000L);
        assertThat(response.getFirstKeyAt()).isEqualTo(now.plusMillis(1500));
        assertThat(response.isFinished()).isTrue();

        TestResponse other = new TestResponse(attempt, sentence, now);
        assertThatThrownBy(() -> other.finish("hola", 5000L, 4000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), now))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> other.finish("hola", null, 4000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), now))
                .isInstanceOf(BusinessException.class);
        other.finish("", null, 4000L, true, TestResponse.Counters.ZERO, UUID.randomUUID(), now);
        assertThat(other.isSkipped()).isTrue();
        assertThat(other.getDurationFromFirstKeyMs()).isNull();
    }

    private TestAttempt attempt() {
        SentenceTest test = new SentenceTest("PRUEBA-01", "Dictado 1", UUID.randomUUID());
        test.activate(2, now);
        Student student = new Student("tigre-07", "Colegio", "hash");
        return new TestAttempt(test, student, "app-1.0", "backend-local", now);
    }
}
