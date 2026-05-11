package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DammTest {

    @Test
    void checkDigit_zero_returnsZero() {
        assertThat(Damm.checkDigit(0L)).isEqualTo(0);
    }

    @Test
    void checkDigit_isInRangeZeroToNine() {
        for (long v = 0; v < 200; v++) {
            int c = Damm.checkDigit(v);
            assertThat(c).isBetween(0, 9);
        }
    }

    @Test
    void verify_acceptsItsOwnCheckDigit() {
        long[] samples = {0L, 1L, 9L, 10L, 99L, 1234567890L, 0xCAFEBABEL & 0xFFFFFFFFFFL};
        for (long v : samples) {
            int check = Damm.checkDigit(v);
            assertThat(Damm.verify(v, check)).as("value %d", v).isTrue();
        }
    }

    @Test
    void verify_rejectsAdjacentTransposition_ofUnequalAdjacentDigits() {
        // Damm's published guarantee: every adjacent transposition is detected
        // whenever the two adjacent digits differ. Exhaustive sweep over short
        // numerals.
        for (long n = 10L; n <= 9999L; n++) {
            String s = Long.toString(n);
            for (int i = 0; i + 1 < s.length(); i++) {
                if (s.charAt(i) == s.charAt(i + 1)) {
                    continue; // identical digits transpose to the same string
                }
                char[] swapped = s.toCharArray();
                char tmp = swapped[i];
                swapped[i] = swapped[i + 1];
                swapped[i + 1] = tmp;
                long perturbed = Long.parseLong(new String(swapped));
                if (perturbed == n) {
                    continue; // e.g. leading-zero swap reads as the same long
                }
                assertThat(Damm.checkDigit(perturbed))
                        .as("transposition of %d at position %d → %d", n, i, perturbed)
                        .isNotEqualTo(Damm.checkDigit(n));
            }
        }
    }

    @Test
    void verify_rejectsAnySingleDigitSubstitution() {
        // Exhaustive sweep: every single-digit edit of any 4-digit numeral
        // must change the Damm check digit.
        for (long n = 0L; n <= 9999L; n++) {
            String s = String.format("%04d", n);
            int baseline = Damm.checkDigit(n);
            for (int i = 0; i < s.length(); i++) {
                for (int replacement = 0; replacement < 10; replacement++) {
                    if (replacement == s.charAt(i) - '0') {
                        continue;
                    }
                    char[] perturbed = s.toCharArray();
                    perturbed[i] = (char) ('0' + replacement);
                    long candidate = Long.parseLong(new String(perturbed));
                    assertThat(Damm.checkDigit(candidate))
                            .as("substitution of %s at position %d with %d", s, i, replacement)
                            .isNotEqualTo(baseline);
                }
            }
        }
    }

    @Test
    void verify_rejectsOutOfRangeCheckDigit() {
        assertThat(Damm.verify(123L, -1)).isFalse();
        assertThat(Damm.verify(123L, 10)).isFalse();
    }

    @Test
    void checkDigit_rejectsNegativeValue() {
        assertThatThrownBy(() -> Damm.checkDigit(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
