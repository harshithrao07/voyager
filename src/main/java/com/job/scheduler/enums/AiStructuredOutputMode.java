package com.job.scheduler.enums;

/**
 * Strongest structured-output contract a registered model endpoint has accepted.
 *
 * <p>The value is learned lazily because "OpenAI compatible" does not imply identical structured
 * output support. Voyager starts with JSON Schema, downgrades only after an explicit provider
 * rejection, and remembers the accepted mode for later turns and benchmark reports.
 */
public enum AiStructuredOutputMode {
    UNKNOWN,
    STRICT_JSON_SCHEMA,
    JSON_SCHEMA,
    JSON_OBJECT,
    PROMPT_ONLY
}
