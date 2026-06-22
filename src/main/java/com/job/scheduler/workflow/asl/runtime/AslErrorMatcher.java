package com.job.scheduler.workflow.asl.runtime;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class AslErrorMatcher {

    public boolean matches(JsonNode errorEquals, String error) {
        if (errorEquals == null || !errorEquals.isArray() || error == null) {
            return false;
        }
        for (JsonNode candidate : errorEquals) {
            String pattern = candidate.stringValue();
            if ("States.ALL".equals(pattern)
                    || pattern.equals(error)
                    || ("States.TaskFailed".equals(pattern)
                    && !"States.Timeout".equals(error))) {
                return true;
            }
        }
        return false;
    }
}
