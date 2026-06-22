package com.job.scheduler.workflow.asl.validation;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AslMachineGraphValidator {

    public void validate(
            String startStateName,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (startStateName == null || !states.has(startStateName)) {
            return;
        }

        Map<String, Set<String>> transitions = collectTransitions(states);
        Set<String> reachable = collectReachable(startStateName, transitions);

        for (Map.Entry<String, JsonNode> entry : states.properties()) {
            if (!reachable.contains(entry.getKey())) {
                issues.add(issue(
                        location + ".States." + entry.getKey(),
                        "STATE_UNREACHABLE",
                        "State is not reachable from StartAt"
                ));
            }
        }

        Set<String> terminatingStates = collectTerminatingStates(states);
        Set<String> canReachTermination = collectStatesThatCanReachTermination(
                transitions,
                terminatingStates
        );

        for (String stateName : reachable) {
            if (!canReachTermination.contains(stateName)) {
                issues.add(issue(
                        location + ".States." + stateName,
                        "NO_TERMINATING_PATH",
                        "State cannot reach a successful or failed terminal outcome"
                ));
            }
        }
    }

    private Map<String, Set<String>> collectTransitions(JsonNode states) {
        Map<String, Set<String>> transitions = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : states.properties()) {
            Set<String> targets = new HashSet<>();
            JsonNode state = entry.getValue();
            if (state != null && state.isObject()) {
                addTextTarget(state.get("Next"), states, targets);
                addTextTarget(state.get("Default"), states, targets);
                addChoiceTargets(state.get("Choices"), states, targets);
                addCatchTargets(state.get("Catch"), states, targets);
            }
            transitions.put(entry.getKey(), targets);
        }
        return transitions;
    }

    private void addChoiceTargets(
            JsonNode choices,
            JsonNode states,
            Set<String> targets
    ) {
        if (choices == null || !choices.isArray()) {
            return;
        }
        for (JsonNode choice : choices) {
            if (choice != null && choice.isObject()) {
                addTextTarget(choice.get("Next"), states, targets);
            }
        }
    }

    private void addCatchTargets(
            JsonNode catchers,
            JsonNode states,
            Set<String> targets
    ) {
        if (catchers == null || !catchers.isArray()) {
            return;
        }
        for (JsonNode catcher : catchers) {
            if (catcher != null && catcher.isObject()) {
                addTextTarget(catcher.get("Next"), states, targets);
            }
        }
    }

    private void addTextTarget(
            JsonNode target,
            JsonNode states,
            Set<String> targets
    ) {
        if (target != null && target.isString() && states.has(target.stringValue())) {
            targets.add(target.stringValue());
        }
    }

    private Set<String> collectReachable(
            String startStateName,
            Map<String, Set<String>> transitions
    ) {
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(startStateName);

        while (!pending.isEmpty()) {
            String stateName = pending.removeFirst();
            if (!reachable.add(stateName)) {
                continue;
            }
            pending.addAll(transitions.getOrDefault(stateName, Set.of()));
        }
        return reachable;
    }

    private Set<String> collectTerminatingStates(JsonNode states) {
        Set<String> terminalStates = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : states.properties()) {
            JsonNode state = entry.getValue();
            if (state == null || !state.isObject()) {
                continue;
            }

            String type = state.path("Type").isString()
                    ? state.path("Type").stringValue()
                    : null;
            if ("Succeed".equals(type)
                    || "Fail".equals(type)
                    || state.path("End").isBoolean() && state.path("End").booleanValue()
                    || "Choice".equals(type) && !state.has("Default")) {
                terminalStates.add(entry.getKey());
            }
        }
        return terminalStates;
    }

    private Set<String> collectStatesThatCanReachTermination(
            Map<String, Set<String>> transitions,
            Set<String> terminalStates
    ) {
        Map<String, Set<String>> predecessors = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : transitions.entrySet()) {
            predecessors.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>());
            for (String target : entry.getValue()) {
                predecessors.computeIfAbsent(target, ignored -> new HashSet<>())
                        .add(entry.getKey());
            }
        }

        Set<String> canTerminate = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(terminalStates);
        while (!pending.isEmpty()) {
            String stateName = pending.removeFirst();
            if (!canTerminate.add(stateName)) {
                continue;
            }
            pending.addAll(predecessors.getOrDefault(stateName, Set.of()));
        }
        return canTerminate;
    }

    private AslValidationIssue issue(String location, String code, String message) {
        return new AslValidationIssue(
                location,
                AslValidationCategory.ASL,
                code,
                message
        );
    }
}
