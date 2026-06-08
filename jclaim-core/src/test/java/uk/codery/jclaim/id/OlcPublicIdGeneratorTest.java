package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OlcPublicIdGeneratorTest {

    @Test
    void generatesOnlyOlcDataSymbolsLiteralsAndDecimalCheck() {
        OlcPublicIdGenerator g = new OlcPublicIdGenerator(new Random(1L));
        for (int i = 0; i < 1000; i++) {
            String id = g.generate();
            // ????-????-? : 8 OLC data symbols, a literal '-', then a decimal check digit.
            assertThat(id).matches("[23456789CFGHJMPQRVWX]{4}-[23456789CFGHJMPQRVWX]{4}-[0-9]");
        }
    }

    @Test
    void everyGeneratedIdValidatesAgainstItsFormat() {
        OlcPublicIdGenerator g = new OlcPublicIdGenerator(new Random(2L));
        for (int i = 0; i < 1000; i++) {
            String id = g.generate();
            assertThat(g.isValid(id)).as("id %s", id).isTrue();
        }
    }

    @Test
    void sameSeedProducesSameId() {
        assertThat(new OlcPublicIdGenerator(new Random(99L)).generate())
                .isEqualTo(new OlcPublicIdGenerator(new Random(99L)).generate());
    }

    @Test
    void checkDigitCatchesSingleSymbolSubstitution() {
        OlcPublicIdGenerator g = new OlcPublicIdGenerator(new Random(3L));
        String id = g.generate();
        // Mutate the first data symbol to a different OLC symbol.
        char first = id.charAt(0);
        char replacement = first == '2' ? '3' : '2';
        String tampered = replacement + id.substring(1);
        assertThat(g.isValid(tampered)).isFalse();
    }

    @Test
    void checkDigitCatchesAdjacentTransposition() {
        OlcPublicIdGenerator g = new OlcPublicIdGenerator(new Random(4L));
        // Find an id whose first two data symbols differ, so swapping them changes the value.
        String id = g.generate();
        while (id.charAt(0) == id.charAt(1)) {
            id = g.generate();
        }
        String swapped = "" + id.charAt(1) + id.charAt(0) + id.substring(2);
        assertThat(g.isValid(swapped)).isFalse();
    }

    @Test
    void customTemplateIsHonoured() {
        OlcPublicIdGenerator g = new OlcPublicIdGenerator(
                PublicIdFormat.ofTemplate("??-??-?", OlcAlphabet.INSTANCE), new Random(5L));
        String id = g.generate();
        assertThat(id).matches("[23456789CFGHJMPQRVWX]{2}-[23456789CFGHJMPQRVWX]{2}-[0-9]");
        assertThat(g.isValid(id)).isTrue();
    }

    @Test
    void rejectsNullFormatAndEntropy() {
        assertThatThrownBy(() -> new OlcPublicIdGenerator((PublicIdFormat) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OlcPublicIdGenerator(
                PublicIdFormat.ofTemplate("??", OlcAlphabet.INSTANCE), (java.util.function.Supplier<Long>) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void defaultConstructorProducesValidOlcId() {
        OlcPublicIdGenerator g = new OlcPublicIdGenerator();
        String id = g.generate();
        assertThat(id).matches("[23456789CFGHJMPQRVWX]{4}-[23456789CFGHJMPQRVWX]{4}-[0-9]");
        assertThat(g.isValid(id)).isTrue();
    }

    @Test
    void formatAccessorReturnsConfiguredFormat() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("??-??-?", OlcAlphabet.INSTANCE);
        OlcPublicIdGenerator g = new OlcPublicIdGenerator(f, new Random(1L));
        assertThat(g.format()).isSameAs(f);
    }
}
