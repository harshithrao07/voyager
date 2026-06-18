package com.job.scheduler.storage;

public record StoredPayload(
        String inlineValue,
        String reference
) {
    public boolean isInline() {
        return inlineValue != null;
    }
}
