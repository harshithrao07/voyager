package com.job.scheduler.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAiThinkingStreamTest {

    @Test
    void separatesReasoningFromAnswer() {
        Transcript transcript = feed("<think>weighing options</think>{\"stage\":\"ASL_READY\"}");

        assertThat(transcript.thinking()).isEqualTo("weighing options");
        assertThat(transcript.answer()).isEqualTo("{\"stage\":\"ASL_READY\"}");
    }

    @Test
    void handlesTagSplitAcrossTokens() {
        Transcript transcript = feed("<th", "ink>", "step one", "</thi", "nk>", "{\"a\":1}");

        assertThat(transcript.thinking()).isEqualTo("step one");
        assertThat(transcript.answer()).isEqualTo("{\"a\":1}");
    }

    @Test
    void treatsUntaggedOutputAsAnswer() {
        Transcript transcript = feed("{\"stage\":", "\"PLAN_READY\"}");

        assertThat(transcript.thinking()).isEmpty();
        assertThat(transcript.answer()).isEqualTo("{\"stage\":\"PLAN_READY\"}");
    }

    @Test
    void supportsTheThinkingTagVariantAndRepeatedBlocks() {
        Transcript transcript = feed(
                "<thinking>first</thinking>",
                "{\"a\":1}",
                "<think>second</think>",
                "{\"b\":2}"
        );

        assertThat(transcript.thinking()).isEqualTo("firstsecond");
        assertThat(transcript.answer()).isEqualTo("{\"a\":1}{\"b\":2}");
    }

    @Test
    void releasesAnUnterminatedTagPrefixOnFlush() {
        // A model that stops mid-tag must not silently swallow the held-back tail.
        Transcript transcript = feed("{\"a\":1}", "<thi");

        assertThat(transcript.answer()).isEqualTo("{\"a\":1}<thi");
    }

    @Test
    void doesNotMistakeUnrelatedMarkupForAThinkingTag() {
        Transcript transcript = feed("{\"body\":\"<b>hi</b>\"}");

        assertThat(transcript.thinking()).isEmpty();
        assertThat(transcript.answer()).isEqualTo("{\"body\":\"<b>hi</b>\"}");
    }

    @Test
    void classificationMatchesTheBufferedRegexSplit() {
        // The streamed split must agree with extractThinking(), which parses the whole reply at once.
        String reply = "<think>reason</think>{\"stage\":\"ASL_READY\"}";
        Transcript transcript = feed(reply.split("(?<=\\G.{3})"));

        assertThat(transcript.thinking()).isEqualTo("reason");
        assertThat(transcript.answer()).isEqualTo("{\"stage\":\"ASL_READY\"}");
    }

    private Transcript feed(String... tokens) {
        WorkflowAiThinkingStream stream = new WorkflowAiThinkingStream();
        List<WorkflowAiThinkingStream.Segment> segments = new ArrayList<>();
        for (String token : tokens) {
            segments.addAll(stream.accept(token));
        }
        segments.addAll(stream.flush());

        StringBuilder thinking = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        for (WorkflowAiThinkingStream.Segment segment : segments) {
            if (segment.phase() == WorkflowAiThinkingStream.Phase.THINKING) {
                thinking.append(segment.text());
            } else {
                answer.append(segment.text());
            }
        }
        return new Transcript(thinking.toString(), answer.toString());
    }

    private record Transcript(String thinking, String answer) {
    }
}
