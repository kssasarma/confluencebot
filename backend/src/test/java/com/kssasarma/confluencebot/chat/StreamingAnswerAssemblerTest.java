package com.kssasarma.confluencebot.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingAnswerAssemblerTest {

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

        StringBuilder visible = new StringBuilder();
        visible.append(assembler.accept("The answer.\n"));
        visible.append(assembler.accept("---FOLLOW-UP-QUESTIONS---\n"));
        visible.append(assembler.accept("First?\nSecond?\nThird?"));
        visible.append(assembler.remainder());

        assertThat(visible.toString()).isEqualTo("The answer.\n");
        assertThat(assembler.finish().followUpQuestions())
                .containsExactly("First?", "Second?", "Third?");
    }

    @Test
    void holdsBackTextThatCouldStillTurnIntoTheMarker() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        assembler.accept("Answer.\n");
        // A chunk boundary lands in the middle of the marker — none of it may reach the user.
        String duringMarker = assembler.accept("---FOLLOW");
        String afterMarker = assembler.accept("-UP-QUESTIONS---\nNext?");

        assertThat(duringMarker).isEmpty();
        assertThat(afterMarker).isEmpty();
        assertThat(assembler.remainder()).isEmpty();

        ParsedAnswer parsed = assembler.finish();
        assertThat(parsed.answer()).isEqualTo("Answer.");
        assertThat(parsed.followUpQuestions()).containsExactly("Next?");
    }

    @Test
    void releasesHeldBackTextWhenItTurnsOutNotToBeTheMarker() {
        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        assertThat(assembler.accept("Use the ---")).isEqualTo("Use the ");
        assertThat(assembler.accept("dry-run flag.")).isEqualTo("---dry-run flag.");
        assertThat(assembler.finish().answer()).isEqualTo("Use the ---dry-run flag.");
    }

    @Test
    void parsesACompleteAnswerTheSameWay() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse(
                "Body text.\n---FOLLOW-UP-QUESTIONS---\nA?\nB?\nC?\nD?");

        assertThat(parsed.answer()).isEqualTo("Body text.");
        assertThat(parsed.followUpQuestions()).containsExactly("A?", "B?", "C?");
    }

    @Test
    void blankAnswerYieldsNothing() {
        ParsedAnswer parsed = StreamingAnswerAssembler.parse("   ");

        assertThat(parsed.answer()).isEmpty();
        assertThat(parsed.followUpQuestions()).isEmpty();
    }
}
