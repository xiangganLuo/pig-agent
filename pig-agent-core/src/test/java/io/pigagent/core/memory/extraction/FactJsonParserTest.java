package io.pigagent.core.memory.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fault-tolerant JSON → facts parsing: happy path, prose-wrapping, missing fields, malformed, clamp. */
class FactJsonParserTest {

    @Test
    void parsesArrayOfFacts() {
        String json = """
                [
                  {"subject":"language","category":"user-preference","statement":"Prefers Chinese","confidence":0.95,"correction":false},
                  {"subject":"build-tool","category":"project-fact","statement":"Uses Maven","confidence":0.8}
                ]""";
        List<ExtractedFact> facts = FactJsonParser.parse(json);
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).subject()).isEqualTo("language");
        assertThat(facts.get(0).category()).isEqualTo(FactCategory.USER_PREFERENCE);
        assertThat(facts.get(0).confidence()).isEqualTo(0.95);
        assertThat(facts.get(1).category()).isEqualTo(FactCategory.PROJECT_FACT);
        assertThat(facts.get(1).correction()).isFalse();
    }

    @Test
    void slicesJsonOutOfSurroundingProseAndFences() {
        String raw = "Sure, here are the facts:\n```json\n"
                + "[{\"subject\":\"tz\",\"category\":\"reference\",\"statement\":\"UTC+8\",\"confidence\":0.9}]"
                + "\n```\nHope that helps!";
        List<ExtractedFact> facts = FactJsonParser.parse(raw);
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).subject()).isEqualTo("tz");
        assertThat(facts.get(0).category()).isEqualTo(FactCategory.REFERENCE);
    }

    @Test
    void acceptsWrappedFactsObject() {
        String json = "{\"facts\":[{\"subject\":\"s\",\"category\":\"reference\",\"statement\":\"v\",\"confidence\":0.9}]}";
        assertThat(FactJsonParser.parse(json)).hasSize(1);
    }

    @Test
    void skipsFactsMissingSubjectOrStatement() {
        String json = """
                [
                  {"category":"reference","statement":"no subject","confidence":0.9},
                  {"subject":"s","category":"reference","confidence":0.9},
                  {"subject":"ok","category":"reference","statement":"kept","confidence":0.9}
                ]""";
        List<ExtractedFact> facts = FactJsonParser.parse(json);
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).subject()).isEqualTo("ok");
    }

    @Test
    void unknownCategoryFallsBackToProjectFact() {
        String json = "[{\"subject\":\"s\",\"category\":\"weird\",\"statement\":\"v\",\"confidence\":0.9}]";
        assertThat(FactJsonParser.parse(json).get(0).category()).isEqualTo(FactCategory.PROJECT_FACT);
    }

    @Test
    void clampsConfidenceFromJson() {
        String json = "[{\"subject\":\"s\",\"category\":\"reference\",\"statement\":\"v\",\"confidence\":1.7}]";
        assertThat(FactJsonParser.parse(json).get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void nonJsonOrEmpty_returnsEmpty() {
        assertThat(FactJsonParser.parse(null)).isEmpty();
        assertThat(FactJsonParser.parse("")).isEmpty();
        assertThat(FactJsonParser.parse("no json here at all")).isEmpty();
        assertThat(FactJsonParser.parse("[not valid json}")).isEmpty();
    }
}
