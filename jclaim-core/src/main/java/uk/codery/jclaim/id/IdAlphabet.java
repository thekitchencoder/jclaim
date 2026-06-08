package uk.codery.jclaim.id;

/**
 * A display alphabet for public IDs. Defines how data symbols are rendered and
 * decoded, and how the order-10 Damm check digit (0–9) is rendered as a single
 * check character and decoded back. {@link #radix()} is the alphabet size, which
 * sets the entropy per data symbol.
 */
public interface IdAlphabet {

    /** Number of data symbols in the alphabet (e.g. 32 for Crockford, 20 for OLC). */
    int radix();

    /** Renders a data symbol index in {@code [0, radix())} as its display character.
     *  Behaviour for indices outside that range is undefined. */
    char encode(int index);

    /** Decodes a display character to its data-symbol index, or {@code -1} if it is not one. */
    int decode(char c);

    /** Renders a Damm check digit {@code [0,9]} as its display character. */
    char checkChar(int digit);

    /** Decodes a check character to {@code [0,9]}, or {@code -1} if it is not a valid check char. */
    int decodeCheck(char c);
}
