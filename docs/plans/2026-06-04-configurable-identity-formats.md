# Configurable Identity Formats Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or
> superpowers:subagent-driven-development) to implement this plan task-by-task.
> Each task follows @superpowers:test-driven-development.

**Goal:** Make the URN type segment and the humanId format configurable
per-resolver, with Spring properties, while defaults reproduce today's output
exactly.

**Architecture:** Two independent capabilities in `jclaim-core` (a 3rd URN
capture group on `EntityId`; a new immutable `HumanIdFormat` compiled from a
template that `HumanIdGenerator` delegates to), threaded through
`DefaultEntityResolver.Builder` and surfaced as `jclaim.urn.*` /
`jclaim.human-id.template` in the Spring starter. Single PR.

**Tech Stack:** Java 21, Maven (multi-module), JUnit 5 + AssertJ, Spring Boot
3.5 starter.

**Design reference:** `docs/plans/2026-06-04-configurable-identity-formats-design.md`

**Key facts about the current code (verified):**
- `EntityId` is `record EntityId(String urn)` with a 2-group regex
  (`namespace`, `uuid`) and `:entity:` literal; factories `of(ns, uuid)` /
  `of(uuid)`; accessors `namespace()` (group 1), `uuid()` (group 2).
- `CrockfordBase32`: `ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"`,
  `encode(long, bits)` (bits a multiple of 5, ≤64), `decode(String)` (honours
  `O→0`,`I/L→1` aliases, ignores `-`). `Damm.checkDigit(long)→0..9`,
  `Damm.verify(long,int)→boolean`.
- `HumanIdGenerator`: instance with `Supplier<Long> entropy`; ctors `()`,
  `(Random)`, `(Supplier<Long>)`; `generate()`; **static** `format(long)` and
  `isValid(String)`; constants `DATA_BITS=40`, `DATA_CHARS=8`, `MASK`.
- `DefaultEntityResolver`: builder fields incl. `namespace`,
  `humanIdGenerator`; `mintEntity` calls `EntityId.of(namespace, uuid…)` and
  `humanIdGenerator.generate()`.
- `JclaimProperties`: flat `namespace` (`jclaim.namespace`), accessor
  `namespace()`/`setNamespace`. `JclaimAutoConfiguration` resolver bean calls
  `.namespace(properties.namespace())`.

**Test commands:**
- Core single class: `mvn -q -pl jclaim-core test -Dtest=ClassName`
- Starter single class (builds core too):
  `mvn -q -pl jclaim-spring-boot-starter -am test -Dtest=ClassName`
  (Targeting named classes avoids the Docker-gated `*IntegrationTest`s.)
- Full reactor (needs Docker for integration tests): `mvn -q install`

---

## Task 1: `EntityId` — configurable type segment

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/model/EntityId.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/model/EntityIdTest.java`
  (create if absent)

**Step 1 — Write failing tests.** Add to `EntityIdTest`:

```java
@Test
void buildsUrnWithExplicitType() {
    UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000000");
    EntityId e = EntityId.of("acme", "customer", id);
    assertThat(e.urn()).isEqualTo("urn:acme:customer:" + id);
    assertThat(e.namespace()).isEqualTo("acme");
    assertThat(e.type()).isEqualTo("customer");
    assertThat(e.uuid()).isEqualTo(id);
}

@Test
void twoArgFactoryDefaultsTypeToEntity() {
    UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000000");
    EntityId e = EntityId.of("acme", id);
    assertThat(e.urn()).isEqualTo("urn:acme:entity:" + id);
    assertThat(e.type()).isEqualTo("entity");
}

@Test
void existingEntityUrnStillParses() {
    EntityId e = new EntityId("urn:codery:entity:018f0000-0000-7000-8000-000000000000");
    assertThat(e.namespace()).isEqualTo("codery");
    assertThat(e.type()).isEqualTo("entity");
}

@Test
void rejectsBlankType() {
    UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000000");
    assertThatThrownBy(() -> EntityId.of("acme", "  ", id))
            .isInstanceOf(IllegalArgumentException.class);
}
```

**Step 2 — Run, verify fail:**
`mvn -q -pl jclaim-core test -Dtest=EntityIdTest`
Expected: FAIL (`of(String,String,UUID)` / `type()` don't exist).

**Step 3 — Implement.** In `EntityId.java`:
- Add `public static final String DEFAULT_TYPE = "entity";`
- Replace `URN_PATTERN` with a 3-group version:
  ```java
  private static final Pattern URN_PATTERN = Pattern.compile(
          "^urn:([A-Za-z0-9][A-Za-z0-9-]*):([A-Za-z0-9][A-Za-z0-9-]*):"
          + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$"
  );
  ```
- Add the new factory and re-point the existing ones:
  ```java
  public static EntityId of(String namespace, String type, UUID uuid) {
      Objects.requireNonNull(namespace, "namespace");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(uuid, "uuid");
      if (namespace.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
      if (type.isBlank()) throw new IllegalArgumentException("type must not be blank");
      return new EntityId("urn:" + namespace + ":" + type + ":" + uuid);
  }

  public static EntityId of(String namespace, UUID uuid) {
      return of(namespace, DEFAULT_TYPE, uuid);
  }
  ```
- `namespace()` stays group 1. Add `type()` returning group 2. Change `uuid()`
  to parse group **3**.
- Update the class javadoc to `urn:<namespace>:<type>:<UUID v7>`.

**Step 4 — Run, verify pass:**
`mvn -q -pl jclaim-core test -Dtest=EntityIdTest` → PASS.

**Step 5 — Commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/model/EntityId.java \
        jclaim-core/src/test/java/uk/codery/jclaim/model/EntityIdTest.java
git commit -m "feat(core): configurable URN type segment on EntityId"
```

---

## Task 2: `HumanIdFormat` — template compile + `format`

**Files:**
- Create: `jclaim-core/src/main/java/uk/codery/jclaim/id/HumanIdFormat.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/id/HumanIdFormatTest.java`

**Step 1 — Write failing tests:**

```java
class HumanIdFormatTest {

    @Test
    void defaultReproducesLegacyFormat() {
        // Golden: same value the old HumanIdGenerator.format produced for 0.
        // 0 → "0000-0000-" + Damm(0). Damm.checkDigit(0)=0 → '0'.
        assertThat(HumanIdFormat.DEFAULT.format(0L)).isEqualTo("0000-0000-0");
    }

    @Test
    void defaultIsEightDataCharsPlusCheck() {
        String id = HumanIdFormat.DEFAULT.format(0x0123456789L);
        assertThat(id).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9]");
        assertThat(HumanIdFormat.DEFAULT.dataBits()).isEqualTo(40);
    }

    @Test
    void prefixTemplateRenders() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("JG??????"); // JG + 5 data + check
        String id = f.format(0L);
        assertThat(id).startsWith("JG");
        assertThat(id).hasSize(8);           // 2 literal + 5 data + 1 check
        assertThat(f.dataBits()).isEqualTo(25);
    }

    @Test
    void literalHashAndShortDataRender() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("#?????"); // '#' + 4 data + check
        assertThat(f.dataBits()).isEqualTo(20);
        assertThat(f.format(0L)).isEqualTo("#00000"); // '#' + "0000" + check '0'
    }

    @Test
    void rejectsTemplateWithFewerThanTwoPlaceholders() {
        assertThatThrownBy(() -> HumanIdFormat.ofTemplate("AB-?"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTemplateOverSixtyBitCeiling() {
        // 13 data + 1 check = 14 '?' → 65 data bits → rejected (max 12 data).
        assertThatThrownBy(() -> HumanIdFormat.ofTemplate("?".repeat(14)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

**Step 2 — Run, verify fail:**
`mvn -q -pl jclaim-core test -Dtest=HumanIdFormatTest`
Expected: FAIL (class missing).

**Step 3 — Implement `HumanIdFormat`** (validation + `format`; `isValid` lands
in Task 3):

```java
package uk.codery.jclaim.id;

import java.util.Objects;

/**
 * Immutable humanId format compiled from a template. In the template, '?' is a
 * placeholder: the LAST '?' renders the Damm check digit, every other '?'
 * renders a random Crockford Base32 data symbol; any other character is a
 * literal emitted verbatim. Every template char maps to exactly one output
 * char, so formatting and validation are fixed-width position walks.
 */
public final class HumanIdFormat {

    /** 60-bit ceiling keeps the value in a long. */
    private static final int MAX_DATA_CHARS = 12;

    public static final HumanIdFormat DEFAULT = ofTemplate("????-????-?");

    private enum SlotType { LITERAL, DATA, CHECK }
    private record Slot(SlotType type, char literal) {}

    private final Slot[] plan;
    private final int dataChars;
    private final int dataBits;
    private final long mask;

    private HumanIdFormat(Slot[] plan, int dataChars) {
        this.plan = plan;
        this.dataChars = dataChars;
        this.dataBits = dataChars * 5;
        this.mask = (1L << dataBits) - 1L;
    }

    public static HumanIdFormat ofTemplate(String template) {
        Objects.requireNonNull(template, "template");
        int placeholders = 0;
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) == '?') placeholders++;
        }
        if (placeholders < 2) {
            throw new IllegalArgumentException(
                    "template needs >= 2 '?' (>=1 data + 1 check): '" + template + "'");
        }
        int dataChars = placeholders - 1;
        if (dataChars > MAX_DATA_CHARS) {
            throw new IllegalArgumentException(
                    "template has " + dataChars + " data placeholders; max " + MAX_DATA_CHARS
                    + " (60-bit ceiling): '" + template + "'");
        }
        int lastPlaceholder = template.lastIndexOf('?');
        Slot[] plan = new Slot[template.length()];
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '?') {
                plan[i] = (i == lastPlaceholder)
                        ? new Slot(SlotType.CHECK, '?')
                        : new Slot(SlotType.DATA, '?');
            } else {
                plan[i] = new Slot(SlotType.LITERAL, c);
            }
        }
        return new HumanIdFormat(plan, dataChars);
    }

    public int dataBits() {
        return dataBits;
    }

    /** Formats the low {@code dataBits} of {@code value} per this template. */
    public String format(long value) {
        long v = value & mask;
        String data = CrockfordBase32.encode(v, dataBits);
        char checkChar = CrockfordBase32.ALPHABET.charAt(Damm.checkDigit(v));
        StringBuilder sb = new StringBuilder(plan.length);
        int d = 0;
        for (Slot slot : plan) {
            switch (slot.type()) {
                case LITERAL -> sb.append(slot.literal());
                case DATA -> sb.append(data.charAt(d++));
                case CHECK -> sb.append(checkChar);
            }
        }
        return sb.toString();
    }
}
```

**Step 4 — Run, verify pass:**
`mvn -q -pl jclaim-core test -Dtest=HumanIdFormatTest` → PASS.
(If `defaultReproducesLegacyFormat` disagrees, confirm the golden against the
*current* `HumanIdGenerator.format(0L)` before changing the test.)

**Step 5 — Commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/id/HumanIdFormat.java \
        jclaim-core/src/test/java/uk/codery/jclaim/id/HumanIdFormatTest.java
git commit -m "feat(core): HumanIdFormat template compile + format"
```

---

## Task 3: `HumanIdFormat.isValid` — alias-forgiving validation

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/id/HumanIdFormat.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/id/HumanIdFormatTest.java`

**Step 1 — Write failing tests:**

```java
@Test
void validatesRoundTrip() {
    String id = HumanIdFormat.DEFAULT.format(0x9ABCDEF012L);
    assertThat(HumanIdFormat.DEFAULT.isValid(id)).isTrue();
}

@Test
void forgivesCrockfordAliasesInDataAndCheck() {
    // Build a valid id, then swap a '0'->'O' and '1'->'I' (data) and, if the
    // check digit is 0 or 1, swap it too — all must still validate.
    String id = HumanIdFormat.DEFAULT.format(0L); // "0000-0000-0"
    String aliased = id.replace('0', 'O');         // every 0 -> letter O, incl. check
    assertThat(HumanIdFormat.DEFAULT.isValid(aliased)).isTrue();
}

@Test
void rejectsWrongCheckDigit() {
    String id = HumanIdFormat.DEFAULT.format(0L); // "0000-0000-0"
    String bad = id.substring(0, id.length() - 1) + "1"; // flip check 0 -> 1
    assertThat(HumanIdFormat.DEFAULT.isValid(bad)).isFalse();
}

@Test
void rejectsLiteralMismatchAndWrongLength() {
    HumanIdFormat f = HumanIdFormat.ofTemplate("ID????-????-?");
    String id = f.format(0L);
    assertThat(f.isValid("XX" + id.substring(2))).isFalse(); // literal "ID" broken
    assertThat(f.isValid(id + "Z")).isFalse();               // too long
    assertThat(f.isValid(null)).isFalse();
}

@Test
void damm0and1RenderAsDigits() {
    // Regression for the 0/1 discussion: check digit prints as a plain digit.
    assertThat(HumanIdFormat.DEFAULT.format(0L)).endsWith("0"); // Damm(0)=0
    // Find a value whose Damm digit is 1 and assert it ends with '1'.
    long v = 0L;
    while (uk.codery.jclaim.id.Damm.checkDigit(v) != 1) v++;
    assertThat(HumanIdFormat.DEFAULT.format(v)).endsWith("1");
}
```

**Step 2 — Run, verify fail** (no `isValid`):
`mvn -q -pl jclaim-core test -Dtest=HumanIdFormatTest`

**Step 3 — Implement** `isValid` + private `decode` on `HumanIdFormat`:

```java
/** True iff {@code candidate} fits this template and its Damm check digit holds. */
public boolean isValid(String candidate) {
    if (candidate == null || candidate.length() != plan.length) {
        return false;
    }
    long acc = 0L;
    int check = -1;
    for (int i = 0; i < plan.length; i++) {
        char c = candidate.charAt(i);
        Slot slot = plan[i];
        switch (slot.type()) {
            case LITERAL -> { if (c != slot.literal()) return false; }
            case DATA -> {
                int d = decodeSymbol(c);
                if (d < 0) return false;
                acc = (acc << 5) | d;
            }
            case CHECK -> {
                int d = decodeSymbol(c);
                if (d < 0 || d > 9) return false;
                check = d;
            }
        }
    }
    return Damm.verify(acc, check);
}

/** Decodes one Crockford symbol (honouring O/I/L aliases); -1 if invalid. */
private static int decodeSymbol(char c) {
    try {
        return (int) CrockfordBase32.decode(String.valueOf(c));
    } catch (IllegalArgumentException ex) {
        return -1;
    }
}
```

**Step 4 — Run, verify pass.**

**Step 5 — Commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/id/HumanIdFormat.java \
        jclaim-core/src/test/java/uk/codery/jclaim/id/HumanIdFormatTest.java
git commit -m "feat(core): HumanIdFormat alias-forgiving isValid"
```

---

## Task 4: `HumanIdGenerator` — delegate to `HumanIdFormat`

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/id/HumanIdGenerator.java`
- Modify: `jclaim-core/src/test/java/uk/codery/jclaim/id/HumanIdGeneratorTest.java`
- Grep + update any other callers of the removed statics.

**Step 1 — Write/adjust failing tests.** Add to `HumanIdGeneratorTest`:

```java
@Test
void generatesWithCustomFormat() {
    HumanIdFormat f = HumanIdFormat.ofTemplate("JG??????");
    HumanIdGenerator gen = new HumanIdGenerator(f, new Random(42));
    String id = gen.generate();
    assertThat(id).startsWith("JG").hasSize(8);
    assertThat(gen.isValid(id)).isTrue();
}

@Test
void defaultGeneratorStillProducesLegacyShape() {
    HumanIdGenerator gen = new HumanIdGenerator(new Random(1));
    assertThat(gen.generate()).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9]");
}
```
Keep any existing tests that exercise `generate()`/round-trip; **delete or
migrate** tests that call the *static* `HumanIdGenerator.format(...)` /
`HumanIdGenerator.isValid(...)` to use `HumanIdFormat.DEFAULT` instead.

**Step 2 — Run, verify fail:**
`mvn -q -pl jclaim-core test -Dtest=HumanIdGeneratorTest`

**Step 3 — Implement.** Rewrite `HumanIdGenerator` to hold a format:

```java
public final class HumanIdGenerator {

    private final HumanIdFormat format;
    private final Supplier<Long> entropy;

    public HumanIdGenerator() { this(HumanIdFormat.DEFAULT); }

    public HumanIdGenerator(HumanIdFormat format) { this(format, new SecureRandom()); }

    public HumanIdGenerator(Random random) { this(HumanIdFormat.DEFAULT, random); }

    public HumanIdGenerator(HumanIdFormat format, Random random) {
        this(format, (Supplier<Long>) random::nextLong);
    }

    public HumanIdGenerator(Supplier<Long> entropy) { this(HumanIdFormat.DEFAULT, entropy); }

    public HumanIdGenerator(HumanIdFormat format, Supplier<Long> entropy) {
        this.format = Objects.requireNonNull(format, "format");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
    }

    /** Mints a fresh human ID (format masks the entropy to its own width). */
    public String generate() { return format.format(entropy.get()); }

    /** Validates against this generator's format. */
    public boolean isValid(String humanId) { return format.isValid(humanId); }

    public HumanIdFormat format() { return format; }
}
```
Remove the static `format(long)` / `isValid(String)` and the `DATA_BITS` /
`DATA_CHARS` / `MASK` constants. Then:
```bash
grep -rn "HumanIdGenerator.format\|HumanIdGenerator.isValid" jclaim-core examples
```
Replace remaining static usages with `HumanIdFormat.DEFAULT.format/​isValid`.

**Step 4 — Run, verify pass:**
`mvn -q -pl jclaim-core test -Dtest=HumanIdGeneratorTest,HumanIdFormatTest`
Then the whole core module to catch migrated callers:
`mvn -q -pl jclaim-core test` → all green.

**Step 5 — Commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/id/HumanIdGenerator.java \
        jclaim-core/src/test/java/uk/codery/jclaim/id/HumanIdGeneratorTest.java
git commit -m "refactor(core): HumanIdGenerator delegates to HumanIdFormat"
```

---

## Task 5: Resolver builder wiring

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/resolver/DefaultEntityResolver.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/resolver/DefaultEntityResolverTest.java`

**Step 1 — Write failing tests:**

```java
@Test
void mintsWithConfiguredEntityType() {
    EntityStorage storage = new InMemoryEntityStorage();
    EntityResolver resolver = DefaultEntityResolver.builder(storage)
            .namespace("acme")
            .entityType("customer")
            .build();
    ResolutionResult r = resolver.resolveOrMint(
            new Claim(SourceSystem.of("crm"), "u-1", List.of()));
    Entity e = ((ResolutionResult.Minted) r).entity();
    assertThat(e.id().urn()).startsWith("urn:acme:customer:");
    assertThat(e.id().type()).isEqualTo("customer");
}

@Test
void mintsHumanIdWithConfiguredTemplate() {
    EntityStorage storage = new InMemoryEntityStorage();
    EntityResolver resolver = DefaultEntityResolver.builder(storage)
            .humanIdTemplate("JG??????")
            .build();
    ResolutionResult r = resolver.resolveOrMint(
            new Claim(SourceSystem.of("crm"), "u-2", List.of()));
    Entity e = ((ResolutionResult.Minted) r).entity();
    assertThat(e.humanId()).startsWith("JG").hasSize(8);
}
```

**Step 2 — Run, verify fail:**
`mvn -q -pl jclaim-core test -Dtest=DefaultEntityResolverTest`

**Step 3 — Implement.** In `DefaultEntityResolver`:
- Add field `private final String entityType;` set from `b.entityType`.
- `mintEntity`: `EntityId.of(namespace, entityType, uuidSupplier.get())`.
- In `Builder`: add `private String entityType = EntityId.DEFAULT_TYPE;` and
  ```java
  public Builder entityType(String entityType) {
      this.entityType = entityType;
      return this;
  }
  public Builder humanIdFormat(HumanIdFormat format) {
      this.humanIdGenerator = new HumanIdGenerator(format);
      return this;
  }
  public Builder humanIdTemplate(String template) {
      return humanIdFormat(HumanIdFormat.ofTemplate(template));
  }
  ```
  (Import `uk.codery.jclaim.id.HumanIdFormat`.) Keep existing
  `humanIdGenerator(...)`.

**Step 4 — Run, verify pass:**
`mvn -q -pl jclaim-core test -Dtest=DefaultEntityResolverTest` then
`mvn -q -pl jclaim-core test` (full module green).

**Step 5 — Commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/resolver/DefaultEntityResolver.java \
        jclaim-core/src/test/java/uk/codery/jclaim/resolver/DefaultEntityResolverTest.java
git commit -m "feat(core): resolver builder entityType + humanId template"
```

---

## Task 6: Spring starter — `jclaim.urn.*` + `jclaim.human-id.template`

**Files:**
- Modify: `jclaim-spring-boot-starter/.../spring/JclaimProperties.java`
- Modify: `jclaim-spring-boot-starter/.../spring/JclaimAutoConfiguration.java`
- Test: `jclaim-spring-boot-starter/.../spring/JclaimPropertiesTest.java`
- Test: `jclaim-spring-boot-starter/.../spring/JclaimAutoConfigurationTest.java`

**Step 1 — Write failing tests.** In `JclaimPropertiesTest` (binding via
`jclaim.urn.namespace`, `jclaim.urn.type`, `jclaim.human-id.template`); replace
any existing `jclaim.namespace` assertion with `jclaim.urn.namespace`:

```java
@Test
void bindsUrnAndHumanIdProperties() {
    JclaimProperties p = bind(Map.of(
            "jclaim.urn.namespace", "acme",
            "jclaim.urn.type", "customer",
            "jclaim.human-id.template", "JG??????"));
    assertThat(p.urn().namespace()).isEqualTo("acme");
    assertThat(p.urn().type()).isEqualTo("customer");
    assertThat(p.humanId().template()).isEqualTo("JG??????");
}

@Test
void urnAndHumanIdDefaults() {
    JclaimProperties p = JclaimProperties.defaults();
    assertThat(p.urn().namespace()).isEqualTo("codery");
    assertThat(p.urn().type()).isEqualTo("entity");
    assertThat(p.humanId().template()).isEqualTo("????-????-?");
}
```
In `JclaimAutoConfigurationTest`, add a context test asserting a minted entity's
URN honours `jclaim.urn.type` and a bad template fails startup:

```java
@Test
void resolverHonoursConfiguredUrnTypeAndTemplate() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues("jclaim.urn.type=customer", "jclaim.human-id.template=JG??????")
        .run(ctx -> {
            EntityResolver r = ctx.getBean(EntityResolver.class);
            Entity e = ((ResolutionResult.Minted) r.resolveOrMint(
                    new Claim(SourceSystem.of("crm"), "u-1", List.of()))).entity();
            assertThat(e.id().type()).isEqualTo("customer");
            assertThat(e.humanId()).startsWith("JG");
        });
}

@Test
void badHumanIdTemplateFailsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues("jclaim.human-id.template=AB") // < 2 placeholders
        .run(ctx -> assertThat(ctx).hasFailed());
}
```

**Step 2 — Run, verify fail:**
`mvn -q -pl jclaim-spring-boot-starter -am test -Dtest=JclaimPropertiesTest,JclaimAutoConfigurationTest`

**Step 3 — Implement.**
- `JclaimProperties`: remove the flat `namespace`/`setNamespace`. Add nested
  `Urn` and `HumanId` types plus fields/accessors:
  ```java
  private Urn urn = new Urn();
  private HumanId humanId = new HumanId();
  public Urn urn() { return urn; }
  public void setUrn(Urn urn) { this.urn = urn; }
  public HumanId humanId() { return humanId; }
  public void setHumanId(HumanId humanId) { this.humanId = humanId; }

  /** URN namespace + type: urn:<namespace>:<type>:<uuid>. */
  public static class Urn {
      private String namespace = "codery";
      private String type = "entity";
      public String namespace() { return namespace; }
      public void setNamespace(String namespace) { this.namespace = namespace; }
      public String type() { return type; }
      public void setType(String type) { this.type = type; }
  }

  /** Human-friendly lookup ID format. */
  public static class HumanId {
      private String template = "????-????-?";
      public String template() { return template; }
      public void setTemplate(String template) { this.template = template; }
  }
  ```
  (Field name `humanId` binds relaxed to `jclaim.human-id`.)
- `JclaimAutoConfiguration` resolver bean: replace
  `.namespace(properties.namespace())` with
  ```java
  .namespace(properties.urn().namespace())
  .entityType(properties.urn().type())
  .humanIdTemplate(properties.humanId().template())
  ```
  (`humanIdTemplate` calls `HumanIdFormat.ofTemplate`, giving eager validation.)

**Step 4 — Run, verify pass:**
`mvn -q -pl jclaim-spring-boot-starter -am test -Dtest=JclaimPropertiesTest,JclaimAutoConfigurationTest`

**Step 5 — Commit:**
```bash
git add jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/JclaimProperties.java \
        jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/JclaimAutoConfiguration.java \
        jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimPropertiesTest.java \
        jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimAutoConfigurationTest.java
git commit -m "feat(starter): jclaim.urn.* and jclaim.human-id.template"
```

---

## Task 7: Docs

**Files:**
- Modify: `jclaim-spring-boot-starter/README.md` (properties table:
  `jclaim.namespace` → `jclaim.urn.namespace`; add `jclaim.urn.type` and
  `jclaim.human-id.template` with the grammar table from the design doc).
- Modify: `README.md` (root) — URN stanza notes the type segment is
  configurable; humanId stanza notes the template.
- Modify: `CLAUDE.md` — update §1 URN scheme and §2 human IDs to say type +
  humanId format are configurable per-resolver / via `jclaim.urn.*` /
  `jclaim.human-id.template`.
- Modify: `CHANGELOG.md` — add entries under Unreleased.

**Step 1 — Make the edits** (docs only; no test).

**Step 2 — Sanity build** (defaults unchanged): `mvn -q -pl jclaim-core test`.

**Step 3 — Commit:**
```bash
git add README.md CLAUDE.md CHANGELOG.md jclaim-spring-boot-starter/README.md
git commit -m "docs: configurable URN type + humanId template"
```

---

## Final verification

- `mvn -q install` — full reactor, all modules green (Docker up for the
  Testcontainers integration tests). Expect the previously-green count plus the
  new unit tests; **zero** failures/errors/skips with Docker available.
- Confirm defaults are byte-identical: an unconfigured resolver still mints
  `urn:codery:entity:<uuid>` and `XXXX-XXXX-X` humanIds.
- Then: final code review across the branch, push, open the single PR.
```

> **For Claude:** REQUIRED SUB-SKILL — after all tasks, use
> superpowers:finishing-a-development-branch.
