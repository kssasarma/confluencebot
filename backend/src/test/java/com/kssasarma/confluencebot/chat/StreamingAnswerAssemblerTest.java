package com.kssasarma.confluencebot.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingAnswerAssemblerTest {

    /** Feeds a whole answer through in slices and returns everything the reader would have seen. */
    private static String stream(StreamingAnswerAssembler assembler, String... chunks) {
        StringBuilder visible = new StringBuilder();
        for (String chunk : chunks) visible.append(assembler.accept(chunk));
        visible.append(assembler.remainder());
        return visible.toString();
    }

    @Test
    void emitsTokensAsTheyArrive() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        assertThat(assembler.accept("Hello ")).isEqualTo("Hello ");
        assertThat(assembler.accept("world")).isEqualTo("world");
        assertThat(assembler.remainder()).isEmpty();
        assertThat(assembler.finish().answer()).isEqualTo("Hello world");
    }

    @Test
    void neverEmitsTheFollowUpMarkerOrWhatComesAfterIt() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        String visible = stream(assembler,
                "The answer.\n", "---FOLLOW-UP-QUESTIONS---\n", "First?\nSecond?\nThird?");

        assertThat(visible).isEqualTo("The answer.\n");
        assertThat(assembler.finish().followUpQuestions())
                .containsExactly("First?", "Second?", "Third?");
    }

    @Test
    void holdsBackTextThatCouldStillTurnIntoTheMarker() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        assembler.accept("Answer.\n");
        String duringMarker = assembler.accept("---FOLLOW");
        String afterMarker = assembler.accept("-UP-QUESTIONS---\nNext?");

        assertThat(duringMarker).isEmpty();
        assertThat(afterMarker).isEmpty();
        assertThat(assembler.remainder()).isEmpty();

        ParsedAnswer parsed = assembler.finish();
        assertThat(parsed.answer()).isEqualTo("Answer.");
        assertThat(parsed.followUpQuestions()).containsExactly("Next?");
    }

    /**
     * Text that briefly looked like the start of a marker is released once it cannot be one — and
     * every character of it is eventually shown, which is the property that matters.
     */
    @Test
    void releasesHeldBackTextWhenItTurnsOutNotToBeTheMarker() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        String visible = stream(assembler, "Use the ---", "dry-run flag.");

        assertThat(visible).isEqualTo("Use the ---dry-run flag.");
        assertThat(assembler.finish().answer()).isEqualTo("Use the ---dry-run flag.");
        assertThat(assembler.finish().followUpQuestions()).isEmpty();
    }

    /**
     * A horizontal rule reduces to no letters, so it is a marker candidate and is held back —
     * briefly. It must still reach the reader by the end of the stream.
     */
    @Test
    void aTrailingHorizontalRuleIsEventuallyShown() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        assertThat(stream(assembler, "Answer.\n", "---")).isEqualTo("Answer.\n---");
    }

    // ── Tolerant marker recognition ───────────────────────────────────────────

    /**
     * The reason follow-ups looked broken: models reformat the marker constantly — bolding it,
     * fencing it, turning it into a heading, changing the dashes — and an exact string match
     * misses every one of those. The block is then rendered as part of the answer and the
     * suggestions come back empty.
     */
    @ParameterizedTest(name = "recognises the marker written as {0}")
    @ValueSource(strings = {
            "---FOLLOW-UP-QUESTIONS---",
            "FOLLOW-UP-QUESTIONS",
            "**FOLLOW-UP-QUESTIONS**",
            "## Follow-up questions",
            "Follow-up questions:",
            "Follow up questions",
            "--- FOLLOW UP QUESTIONS ---",
            "___FOLLOW-UP-QUESTIONS___",
            "`FOLLOW-UP-QUESTIONS`",
            "- follow-up-questions -",
    })
    void recognisesAReformattedMarker(String marker) {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse(
                "The answer.\n" + marker + "\nFirst?\nSecond?");

        assertThat(parsed.answer()).isEqualTo("The answer.");
        assertThat(parsed.followUpQuestions()).containsExactly("First?", "Second?");
    }

    @Test
    void doesNotMistakeProseForTheMarker() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse(
                "Follow the upgrade guide first.\nQuestions go to the platform team.");

        assertThat(parsed.answer())
                .isEqualTo("Follow the upgrade guide first.\nQuestions go to the platform team.");
        assertThat(parsed.followUpQuestions()).isEmpty();
    }

    @Test
    void stripsNumberingAndBulletsFromTheQuestions() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse("""
                Body.
                ---FOLLOW-UP-QUESTIONS---
                1. How do I roll back?
                2) What does the flag do?
                - Where are the logs?
                """);

        assertThat(parsed.followUpQuestions()).containsExactly(
                "How do I roll back?", "What does the flag do?", "Where are the logs?");
    }

    @Test
    void ignoresADanglingCodeFenceAroundTheBlock() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse("""
                Body.
                ```
                FOLLOW-UP-QUESTIONS
                First?
                ```
                """);

        assertThat(parsed.answer()).isEqualTo("Body.");
        assertThat(parsed.followUpQuestions()).containsExactly("First?");
    }

    @Test
    void handlesAMarkerOnTheFinalLineWithNoTrailingNewline() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        String visible = stream(assembler, "Body.\n", "FOLLOW-UP-QUESTIONS");

        assertThat(visible).isEqualTo("Body.\n");
        assertThat(assembler.finish().answer()).isEqualTo("Body.");
    }

    @Test
    void capsTheNumberOfSuggestions() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse(
                "Body text.\n---FOLLOW-UP-QUESTIONS---\nA?\nB?\nC?\nD?");

        assertThat(parsed.answer()).isEqualTo("Body text.");
        assertThat(parsed.followUpQuestions()).containsExactly("A?", "B?", "C?");
    }

    @Test
    void parsesACompleteAnswerTheSameWayAsAStreamedOne() {
        String raw = "Body text.\n---FOLLOW-UP-QUESTIONS---\nA?\nB?";

        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();
        stream(assembler, raw);

        assertThat(assembler.finish()).isEqualTo(StreamingAnswerAssembler.parse(raw));
    }

    @Test
    void blankAnswerYieldsNothing() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse("   ");

        assertThat(parsed.answer()).isEmpty();
        assertThat(parsed.followUpQuestions()).isEmpty();
    }

    @Test
    void nullAnswerYieldsNothing() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse(null);

        assertThat(parsed.answer()).isEmpty();
        assertThat(parsed.followUpQuestions()).isEmpty();
    }
}
