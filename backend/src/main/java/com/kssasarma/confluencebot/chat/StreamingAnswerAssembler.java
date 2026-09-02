package com.kssasarma.confluencebot.chat;

import java.util.Arrays;
import java.util.List;

/**
 * Reassembles a streamed answer while keeping the follow-up-question block out of the visible text.
 *
 * The model is asked to append its suggested follow-ups after a fixed marker. Tokens arrive in
 * arbitrary slices, so the marker can straddle two chunks: whatever trailing text could still turn
 * out to be the start of the marker is held back until the next chunk resolves it. Everything
 * before the marker is released as soon as it is known to be safe, which is what keeps the answer
 * appearing token by token.
 *
 * Not thread-safe: one instance belongs to one stream.
 */
public final class StreamingAnswerAssembler {

    /** Separates the answer from the follow-up questions the model appends. */
    public static final String FOLLOW_UP_MARKER = "---FOLLOW-UP-QUESTIONS---";
    private static final int MAX_FOLLOW_UPS = 3;

    private final StringBuilder raw = new StringBuilder();
    private int emittedLength;
    private int markerIndex = -1;

    /**
     * Adds a streamed chunk.
     *
     * @return the text that can safely be shown to the user now — often empty
     */
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        raw.append(chunk);

        if (markerIndex < 0) {
            markerIndex = raw.indexOf(FOLLOW_UP_MARKER);
        }

        int safeEnd = markerIndex >= 0 ? markerIndex : raw.length() - heldBackLength();
        if (safeEnd <= emittedLength) return "";

        String visible = raw.substring(emittedLength, safeEnd);
        emittedLength = safeEnd;
        return visible;
    }

    /** Flushes whatever was held back and returns the answer split from its follow-up questions. */
    public ParsedAnswer finish() {
        if (markerIndex < 0) markerIndex = raw.indexOf(FOLLOW_UP_MARKER);
        return parse(raw.toString());
    }

    /** The tail that is still pending release once the stream ends. */
    public String remainder() {
        int end = markerIndex >= 0 ? markerIndex : raw.length();
        if (end <= emittedLength) return "";
        String tail = raw.substring(emittedLength, end);
        emittedLength = end;
        return tail;
    }

    /** Splits a complete, non-streamed answer the same way. */
    public static ParsedAnswer parse(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return new ParsedAnswer("", List.of());
        }

        int index = rawAnswer.indexOf(FOLLOW_UP_MARKER);
        if (index < 0) {
            return new ParsedAnswer(rawAnswer.strip(), List.of());
        }

        String answer = rawAnswer.substring(0, index).strip();
        List<String> followUps = Arrays.stream(
                        rawAnswer.substring(index + FOLLOW_UP_MARKER.length()).strip().split("\n"))
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .limit(MAX_FOLLOW_UPS)
                .toList();

        return new ParsedAnswer(answer, followUps);
    }

    /**
     * Length of the trailing text that is a prefix of the marker, and so cannot be released yet.
     */
    private int heldBackLength() {
        int maxOverlap = Math.min(raw.length(), FOLLOW_UP_MARKER.length() - 1);
        for (int length = maxOverlap; length > 0; length--) {
            if (endsWithPrefixOfMarker(length)) return length;
        }
        return 0;
    }

    private boolean endsWithPrefixOfMarker(int length) {
        int start = raw.length() - length;
        for (int i = 0; i < length; i++) {
            if (raw.charAt(start + i) != FOLLOW_UP_MARKER.charAt(i)) return false;
        }
        return true;
    }
}
