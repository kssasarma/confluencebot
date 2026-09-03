package com.kssasarma.confluencebot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reassembles a streamed answer while keeping the follow-up-question block out of the visible text.
 *
 * <p>The model is asked to append its suggested follow-ups after a marker line. Two things make
 * that harder than a substring search:
 *
 * <ol>
 *   <li><b>Tokens arrive in arbitrary slices</b>, so the marker can straddle two chunks. Whatever
 *       trailing text could still turn out to be the marker is held back until the next chunk
 *       resolves it; everything before it is released immediately, which is what keeps the answer
 *       appearing token by token.</li>
 *   <li><b>Models reformat the marker.</b> They bold it, fence it, change the dashes, or add a
 *       colon. An exact-string match misses all of those, and the block is then rendered as part
 *       of the answer while the follow-ups come back empty — which is precisely how this feature
 *       came to look broken. Recognition is therefore shape-based: a line whose letters spell
 *       {@code FOLLOWUPQUESTIONS} once decoration is stripped, and which contains nothing else.</li>
 * </ol>
 *
 * <p>Not thread-safe: one instance belongs to one stream.
 */
public final class StreamingAnswerAssembler {

    /** The exact form the prompt asks for. Recognition is tolerant; the request stays specific. */
    public static final String FOLLOW_UP_MARKER = "---FOLLOW-UP-QUESTIONS---";

    private static final int MAX_FOLLOW_UPS = 3;

    /** The letters a marker line reduces to once decoration and separators are removed. */
    private static final String MARKER_LETTERS = "FOLLOWUPQUESTIONS";

    /** Decoration a model may wrap the marker in: rules, bullets, headings, bold, fences, colons. */
    private static final Pattern DECORATION = Pattern.compile("[-*#_`~:\\s]+");

    /** Leading list markers a model may prefix its questions with. */
    private static final Pattern QUESTION_PREFIX = Pattern.compile("^\\s*(?:[-*+•]|\\d+[.)])\\s*");

    /** A line that is nothing but a code fence. */
    private static final Pattern FENCE_LINE = Pattern.compile("^(?:`{3,}|~{3,})$");

    private final StringBuilder raw = new StringBuilder();

    /** How much of {@link #raw} has already been handed to the reader. */
    private int emittedLength;

    /** Start of the marker line in {@link #raw}, or -1 while it has not been seen. */
    private int markerStart = -1;

    /** First index past the marker line, valid once {@link #markerStart} is set. */
    private int markerEnd = -1;

    /** Start of the trailing, not-yet-terminated line — the only text that still needs scanning. */
    private int lineStart;

    /**
     * Adds a streamed chunk.
     *
     * @return the text that can safely be shown to the user now — often empty
     */
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        raw.append(chunk);
        return release(safeEnd(false));
    }

    /** The tail that is still pending release once the stream ends. */
    public String remainder() {
        return release(safeEnd(true));
    }

    /** Flushes whatever was held back and returns the answer split from its follow-up questions. */
    public ParsedAnswer finish() {
        locateMarker(true);
        if (markerStart < 0) return new ParsedAnswer(raw.toString().strip(), List.of());

        return new ParsedAnswer(
                trimDanglingFence(raw.substring(0, markerStart).strip()),
                parseFollowUps(raw.substring(markerEnd)));
    }

    /** Splits a complete, non-streamed answer the same way. */
    public static ParsedAnswer parse(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) return new ParsedAnswer("", List.of());

        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();
        assembler.raw.append(rawAnswer);
        return assembler.finish();
    }

    // ── Streaming internals ───────────────────────────────────────────────────

    /**
     * The furthest point that is safe to show.
     *
     * <p>Everything before the marker is the answer. Before the marker is seen, the trailing
     * incomplete line is held back only while it could still <em>become</em> the marker — usually
     * for a single token, and never long enough to be visible.
     */
    private int safeEnd(boolean streamEnded) {
        locateMarker(streamEnded);
        if (markerStart >= 0) return markerStart;
        if (streamEnded) return raw.length();
        return couldBecomeMarker(raw.substring(lineStart)) ? lineStart : raw.length();
    }

    private String release(int safeEnd) {
        if (safeEnd <= emittedLength) return "";
        String visible = raw.substring(emittedLength, safeEnd);
        emittedLength = safeEnd;
        return visible;
    }

    /**
     * Scans the lines that have arrived since the last call for the marker.
     *
     * <p>Only terminated lines are tested while the stream is live: a line that has not met its
     * newline yet may still gain text that disqualifies it. Once the stream ends, the final line
     * is tested too, since nothing more is coming.
     */
    private void locateMarker(boolean streamEnded) {
        if (markerStart >= 0) return;

        int cursor = lineStart;
        while (cursor < raw.length()) {
            int newline = raw.indexOf("\n", cursor);
            if (newline < 0) break;

            if (isMarkerLine(raw.substring(cursor, newline))) {
                markerStart = cursor;
                markerEnd = newline + 1;
                return;
            }
            cursor = newline + 1;
        }

        lineStart = cursor;

        if (streamEnded && lineStart < raw.length() && isMarkerLine(raw.substring(lineStart))) {
            markerStart = lineStart;
            markerEnd = raw.length();
        }
    }

    // ── Marker recognition ────────────────────────────────────────────────────

    /** True when the line carries the marker words and nothing else. */
    private static boolean isMarkerLine(String line) {
        return MARKER_LETTERS.equals(letters(line));
    }

    /**
     * True when the partial line could still grow into a marker line.
     *
     * <p>A line of pure decoration reduces to nothing and so is a candidate — that is the
     * {@code ---} at the start of the marker, and also a plain horizontal rule, which is simply
     * held until the stream ends and then released. The cost of a false candidate is one token of
     * latency; the cost of a false negative is the marker appearing in the answer.
     */
    private static boolean couldBecomeMarker(String partialLine) {
        String letters = letters(partialLine);
        return MARKER_LETTERS.startsWith(letters);
    }

    /** The line's letters, with all decoration and separators removed. */
    private static String letters(String line) {
        String stripped = DECORATION.matcher(line).replaceAll("");
        return stripped.toUpperCase(java.util.Locale.ROOT);
    }

    // ── Follow-up parsing ─────────────────────────────────────────────────────

    /**
     * Drops a code fence the model opened for the follow-up block and never closed.
     *
     * Counting the fences is what tells that apart from the closing fence of a genuine code
     * block: an odd count means the last one is still open, and a fence left hanging at the very
     * end of an answer renders as an empty code box.
     */
    private static String trimDanglingFence(String answer) {
        long fences = answer.lines()
                .filter(line -> FENCE_LINE.matcher(line.strip()).matches())
                .count();
        if (fences % 2 == 0) return answer;

        int lastNewline = answer.lastIndexOf('\n');
        String lastLine = lastNewline < 0 ? answer : answer.substring(lastNewline + 1);
        if (!FENCE_LINE.matcher(lastLine.strip()).matches()) return answer;

        return lastNewline < 0 ? "" : answer.substring(0, lastNewline).stripTrailing();
    }

    /**
     * Reads the questions that follow the marker.
     *
     * <p>Numbering and bullets are stripped even though the prompt asks for neither: models add
     * them, and a question rendered as "1. How do I…" in a suggestion chip looks like a defect.
     * A stray closing fence is dropped for the same reason.
     */
    private static List<String> parseFollowUps(String block) {
        List<String> questions = new ArrayList<>(MAX_FOLLOW_UPS);

        for (String line : block.split("\n")) {
            String question = QUESTION_PREFIX.matcher(line.strip()).replaceFirst("").strip();
            if (question.isBlank() || letters(question).isEmpty()) continue;

            questions.add(question);
            if (questions.size() == MAX_FOLLOW_UPS) break;
        }

        return List.copyOf(questions);
    }
}
