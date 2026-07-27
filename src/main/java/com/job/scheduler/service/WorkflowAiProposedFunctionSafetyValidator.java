package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionTestCaseDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Safety boundary for AI-proposed function code.
 *
 * <p>Generated functions are persisted and published after approval, so prompt instructions alone
 * are not sufficient. This validator rejects credential placeholders and likely embedded secrets,
 * and verifies test-case JSON before any function row is created.
 */
@Component
@RequiredArgsConstructor
public class WorkflowAiProposedFunctionSafetyValidator {

    private static final Pattern PLACEHOLDER_CREDENTIAL = Pattern.compile(
            """
            (?ix)
            (?:your)[_\\s-]*(?:api[_\\s-]*key|access[_\\s-]*token|auth[_\\s-]*token|
                token|client[_\\s-]*secret|secret|password)
            |(?:replace|change)[_\\s-]*me
            |insert[_\\s-]*(?:api[_\\s-]*key|token|secret|password)[_\\s-]*here
            |(?:api[_\\s-]*key|token|secret|password)[_\\s-]*(?:here|goes[_\\s-]*here)
            |<\\s*(?:api[_\\s-]*key|token|secret|password)\\s*>
            """
    );
    private static final Pattern CREDENTIAL_LITERAL_ASSIGNMENT = Pattern.compile(
            """
            (?ix)
            \\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|authorization|
                client[_-]?secret|password|passwd|secret)\\b
            \\s*[:=]\\s*["'][^"'\\r\\n]{8,}["']
            """
    );
    private static final List<Pattern> KNOWN_SECRET_FORMATS = List.of(
            Pattern.compile("(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)\\bBearer\\s+[a-z0-9._~+/=-]{12,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\bsk_(?:live|test)_[A-Za-z0-9]{16,}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{12,}\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}\\b"),
            Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/:@]+:[^\\s/@]+@")
    );

    private final ObjectMapper objectMapper;

    public List<String> validate(WorkflowAiProposedFunctionDTO function) {
        if (function == null) {
            return List.of("Proposed function cannot be null.");
        }

        List<String> issues = new ArrayList<>();
        String sourceCode = function.sourceCode();
        if (sourceCode != null && !sourceCode.isBlank()) {
            if (PLACEHOLDER_CREDENTIAL.matcher(sourceCode).find()) {
                issues.add(
                        "sourceCode contains a placeholder credential. Use an MCP server or "
                                + "runtime-provided input instead."
                );
            }
            if (CREDENTIAL_LITERAL_ASSIGNMENT.matcher(sourceCode).find()
                    || KNOWN_SECRET_FORMATS.stream()
                    .anyMatch(pattern -> pattern.matcher(sourceCode).find())) {
                issues.add(
                        "sourceCode appears to contain an embedded credential. Secrets must not be "
                                + "stored in generated functions."
                );
            }
        }

        validateTestCases(function.testCases(), issues);
        return List.copyOf(issues);
    }

    public void assertSafe(WorkflowAiProposedFunctionDTO function) {
        List<String> issues = validate(function);
        if (!issues.isEmpty()) {
            String name = function == null || function.name() == null || function.name().isBlank()
                    ? "Proposed function"
                    : "Function '" + function.name().trim() + "'";
            throw new IllegalArgumentException(name + " was rejected: " + String.join(" ", issues));
        }
    }

    private void validateTestCases(
            List<FunctionTestCaseDTO> testCases,
            List<String> issues
    ) {
        if (testCases == null || testCases.isEmpty()) {
            return;
        }
        for (int index = 0; index < testCases.size(); index++) {
            FunctionTestCaseDTO testCase = testCases.get(index);
            if (testCase == null) {
                issues.add("Test case " + (index + 1) + " cannot be null.");
                continue;
            }
            String label = testCase.name() == null || testCase.name().isBlank()
                    ? "Test case " + (index + 1)
                    : "Test case '" + testCase.name().trim() + "'";
            requireJson(label, "input", testCase.input(), false, issues);
            requireJson(label, "expectedOutput", testCase.expectedOutput(), true, issues);
        }
        try {
            objectMapper.writeValueAsString(testCases);
        } catch (Exception exception) {
            issues.add("testCases could not be serialized.");
        }
    }

    private void requireJson(
            String label,
            String field,
            String value,
            boolean allowBlank,
            List<String> issues
    ) {
        if (value == null || value.isBlank()) {
            if (!allowBlank) {
                issues.add(label + " " + field + " must be valid JSON.");
            }
            return;
        }
        try {
            objectMapper.readTree(value);
        } catch (Exception exception) {
            issues.add(label + " " + field + " must be valid JSON.");
        }
    }
}
