package com.job.scheduler.workflow.asl.runtime;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class AslCatchResolver {
    private final AslErrorMatcher errorMatcher;

    public AslCatchResolver(AslErrorMatcher errorMatcher) {
        this.errorMatcher = errorMatcher;
    }

    public JsonNode resolve(JsonNode catchers, String error) {
        if (catchers == null || !catchers.isArray()) {
            return null;
        }
        for (JsonNode catcher : catchers) {
            if (errorMatcher.matches(catcher.get("ErrorEquals"), error)) {
                return catcher;
            }
        }
        return null;
    }
}
