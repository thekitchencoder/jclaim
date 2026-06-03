package uk.codery.jclaim.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TriStateTest {

    @Test
    void hasExactlyThreeOutcomes() {
        assertThat(TriState.values())
            .containsExactly(TriState.MATCHED, TriState.NOT_MATCHED, TriState.UNDETERMINED);
    }
}
