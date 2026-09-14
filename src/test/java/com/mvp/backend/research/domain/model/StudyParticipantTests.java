package com.mvp.backend.research.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;

class StudyParticipantTests {

    @Test
    void oddNumbersStartAssisted() {
        var participant = new StudyParticipant(mock(ResearchStudy.class), 1);

        assertThat(participant.getPseudonym()).isEqualTo("P-001");
        assertThat(participant.getSequence()).isEqualTo(ParticipantSequence.ASSISTED_FIRST);
        assertThat(participant.nextCondition(0)).isEqualTo(ExperimentCondition.ASSISTED);
        assertThat(participant.nextCondition(1)).isEqualTo(ExperimentCondition.UNASSISTED);
    }

    @Test
    void evenNumbersStartUnassisted() {
        var participant = new StudyParticipant(mock(ResearchStudy.class), 2);

        assertThat(participant.getPseudonym()).isEqualTo("P-002");
        assertThat(participant.getSequence()).isEqualTo(ParticipantSequence.UNASSISTED_FIRST);
        assertThat(participant.nextCondition(0)).isEqualTo(ExperimentCondition.UNASSISTED);
        assertThat(participant.nextCondition(1)).isEqualTo(ExperimentCondition.ASSISTED);
    }

    @Test
    void thirdRunIsNotAllowed() {
        var participant = new StudyParticipant(mock(ResearchStudy.class), 3);

        assertThatThrownBy(() -> participant.nextCondition(2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hasCompletedProtocolAfterTwoRuns() {
        var participant = new StudyParticipant(mock(ResearchStudy.class), 4);

        assertThat(participant.hasCompletedProtocol(0)).isFalse();
        assertThat(participant.hasCompletedProtocol(1)).isFalse();
        assertThat(participant.hasCompletedProtocol(2)).isTrue();
    }
}
