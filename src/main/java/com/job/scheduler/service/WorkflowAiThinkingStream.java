package com.job.scheduler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Classifies a streamed model reply into reasoning text and answer text as tokens arrive.
 *
 * <p>Reasoning-capable local models wrap their scratchpad in {@code <think>...</think>} before
 * emitting the JSON answer. {@code WorkflowAiConversationService#extractThinking} does the same
 * split with a regex once the full reply is buffered; this class does it incrementally so reasoning
 * can be forwarded to the browser while the model is still generating.
 *
 * <p>A tag can straddle a token boundary ({@code "<th"} + {@code "ink>"}), so any trailing text that
 * could still become a tag is held back until the next token disambiguates it. Callers must invoke
 * {@link #flush()} at the end of the stream to release a held-back tail that never completed a tag.
 *
 * <p>Not thread safe: one instance belongs to one model call.
 */
class WorkflowAiThinkingStream {

    enum Phase {
        THINKING,
        ANSWER
    }

    record Segment(Phase phase, String text) {
    }

    private static final String[] OPEN_TAGS = {"<think>", "<thinking>"};
    private static final String[] CLOSE_TAGS = {"</think>", "</thinking>"};
    private static final int LONGEST_TAG = "</thinking>".length();

    private final StringBuilder pending = new StringBuilder();
    private boolean insideThinking;

    /**
     * Consumes one streamed token and returns the segments that can now be classified. May return an
     * empty list when the whole token is held back as a possible partial tag.
     */
    List<Segment> accept(String token) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }
        pending.append(token);
        return drain(false);
    }

    /** Releases any held-back tail at end of stream. */
    List<Segment> flush() {
        return drain(true);
    }

    private List<Segment> drain(boolean atEnd) {
        List<Segment> segments = new ArrayList<>();
        while (true) {
            String buffer = pending.toString();
            String lowered = buffer.toLowerCase(Locale.ROOT);
            String[] tags = insideThinking ? CLOSE_TAGS : OPEN_TAGS;

            int tagIndex = -1;
            int tagLength = 0;
            for (String tag : tags) {
                int found = lowered.indexOf(tag);
                if (found >= 0 && (tagIndex < 0 || found < tagIndex)) {
                    tagIndex = found;
                    tagLength = tag.length();
                }
            }

            if (tagIndex >= 0) {
                emit(segments, buffer.substring(0, tagIndex));
                pending.delete(0, tagIndex + tagLength);
                insideThinking = !insideThinking;
                continue;
            }

            // No complete tag. Emit everything that cannot be the start of one; a trailing '<' run
            // stays buffered until the next token proves whether it opens a tag or is literal text.
            int safeLength = atEnd ? buffer.length() : safeLength(buffer, lowered);
            if (safeLength > 0) {
                emit(segments, buffer.substring(0, safeLength));
                pending.delete(0, safeLength);
            }
            return segments;
        }
    }

    /**
     * Returns how much of the buffer can be released without risking a tag split across tokens: all
     * of it unless the tail is a proper prefix of a tag we are currently looking for.
     */
    private int safeLength(String buffer, String lowered) {
        int maxHeld = Math.min(LONGEST_TAG - 1, buffer.length());
        for (int held = maxHeld; held > 0; held--) {
            String tail = lowered.substring(buffer.length() - held);
            if (!tail.startsWith("<")) {
                continue;
            }
            for (String tag : insideThinking ? CLOSE_TAGS : OPEN_TAGS) {
                if (tag.startsWith(tail)) {
                    return buffer.length() - held;
                }
            }
        }
        return buffer.length();
    }

    private void emit(List<Segment> segments, String text) {
        if (text.isEmpty()) {
            return;
        }
        Phase phase = insideThinking ? Phase.THINKING : Phase.ANSWER;
        if (!segments.isEmpty() && segments.get(segments.size() - 1).phase() == phase) {
            Segment previous = segments.remove(segments.size() - 1);
            segments.add(new Segment(phase, previous.text() + text));
            return;
        }
        segments.add(new Segment(phase, text));
    }
}
