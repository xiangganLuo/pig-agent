package io.pigagent.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    @Test
    void createUsesDefaultNameWhenBlank() {
        assertThat(Session.create(null).name()).isEqualTo(Session.DEFAULT_NAME);
        assertThat(Session.create("  ").name()).isEqualTo(Session.DEFAULT_NAME);
        assertThat(Session.create(null).hasDefaultName()).isTrue();
    }

    @Test
    void createTrimsAndKeepsCustomName() {
        Session s = Session.create("  Data analysis  ");
        assertThat(s.name()).isEqualTo("Data analysis");
        assertThat(s.hasDefaultName()).isFalse();
        assertThat(s.id()).isNotBlank();
        assertThat(s.corrupt()).isFalse();
    }

    @Test
    void withNameDoesNotMutateOriginal() {
        Session original = Session.create("first");
        Session renamed = original.withName("second");
        assertThat(original.name()).isEqualTo("first");
        assertThat(renamed.name()).isEqualTo("second");
        assertThat(renamed.id()).isEqualTo(original.id());
        assertThat(renamed.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void withLastActiveAtDoesNotMutateOriginal() {
        Session original = Session.create("s");
        Instant later = original.lastActiveAt().plusSeconds(60);
        Session touched = original.withLastActiveAt(later);
        assertThat(original.lastActiveAt()).isNotEqualTo(later);
        assertThat(touched.lastActiveAt()).isEqualTo(later);
        assertThat(touched.name()).isEqualTo(original.name());
    }

    @Test
    void deriveNameFallsBackToDefaultForBlank() {
        assertThat(Session.deriveName(null)).isEqualTo(Session.DEFAULT_NAME);
        assertThat(Session.deriveName("   ")).isEqualTo(Session.DEFAULT_NAME);
    }

    @Test
    void deriveNameUsesFirstLine() {
        assertThat(Session.deriveName("Hello there\nsecond line")).isEqualTo("Hello there");
    }

    @Test
    void deriveNameTruncatesLongInput() {
        String name = Session.deriveName("a".repeat(50));
        assertThat(name).endsWith("…");
        assertThat(name.length()).isLessThanOrEqualTo(31);
    }

    @Test
    void freshSessionHasNoCompressionLineage() {
        Session s = Session.create("s");
        assertThat(s.lineageId()).isNull();
        assertThat(s.parentSessionId()).isNull();
        assertThat(s.hasCompressionLineage()).isFalse();
    }

    @Test
    void withCompressionLineageAssignsLineageAndParentWithoutMutating() {
        Session original = Session.create("s");
        Session compressed = original.withCompressionLineage();

        // original untouched (immutability)
        assertThat(original.hasCompressionLineage()).isFalse();
        // compressed records provenance
        assertThat(compressed.hasCompressionLineage()).isTrue();
        assertThat(compressed.lineageId()).isNotBlank();
        assertThat(compressed.parentSessionId()).isNotBlank();
        // identity/metadata preserved
        assertThat(compressed.id()).isEqualTo(original.id());
        assertThat(compressed.name()).isEqualTo(original.name());
        assertThat(compressed.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void secondCompressionKeepsLineageIdButAdvancesParent() {
        Session first = Session.create("s").withCompressionLineage();
        Session second = first.withCompressionLineage();

        assertThat(second.lineageId()).isEqualTo(first.lineageId()); // stable chain id
        assertThat(second.parentSessionId()).isNotEqualTo(first.parentSessionId()); // superseded state advanced
    }

    @Test
    void withNameAndModelIdPreserveLineage() {
        Session compressed = Session.create("s").withCompressionLineage();

        Session renamed = compressed.withName("renamed");
        assertThat(renamed.lineageId()).isEqualTo(compressed.lineageId());
        assertThat(renamed.parentSessionId()).isEqualTo(compressed.parentSessionId());

        Session bound = compressed.withModelId("m-1");
        assertThat(bound.lineageId()).isEqualTo(compressed.lineageId());
        assertThat(bound.parentSessionId()).isEqualTo(compressed.parentSessionId());
    }
}
