package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionRunRequestDTO;
import com.job.scheduler.dto.FunctionRunResultDTO;
import com.job.scheduler.dto.FunctionTestCaseDTO;
import com.job.scheduler.dto.FunctionVersionRequestDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.FunctionInvocationStatus;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Qualifies an AI-created draft before it enters the function catalog.
 *
 * <p>The model derives expected examples from the intended behavior. Judge0 then checks that the
 * submitted code compiles, runs, obeys the stdin/stdout JSON contract, and matches those examples.
 * This is deliberately a basic gate, not a proof of semantic correctness.
 */
@Service
@RequiredArgsConstructor
public class WorkflowAiFunctionQualificationService {

    private static final int MAX_GENERATED_TESTS = 5;

    private final WorkflowAiModelResolver modelResolver;
    private final FunctionInvocationService functionInvocationService;
    private final FunctionRegistryService functionRegistryService;
    private final WorkflowAiProposedFunctionSafetyValidator safetyValidator;
    private final ObjectMapper objectMapper;

    public QualificationResult qualify(
            WorkflowAiProposedFunctionDTO proposal,
            FunctionVersionResponseDTO draft,
            AiModelConfig modelConfig,
            String resourceName
    ) {
        List<FunctionTestCaseDTO> tests;
        try {
            tests = generateTests(proposal, modelConfig);
            WorkflowAiProposedFunctionDTO candidate = new WorkflowAiProposedFunctionDTO(
                    proposal.name(),
                    proposal.description(),
                    proposal.languageId(),
                    proposal.sourceCode(),
                    tests,
                    proposal.rationale()
            );
            List<String> issues = new ArrayList<>(safetyValidator.validate(candidate));
            if (tests.isEmpty()) {
                issues.add("The model did not generate a basic successful test case.");
            }
            if (tests.size() > MAX_GENERATED_TESTS) {
                issues.add("The model generated more than " + MAX_GENERATED_TESTS + " test cases.");
            }
            if (!issues.isEmpty()) {
                return QualificationResult.failed(String.join(" ", issues));
            }
        } catch (RuntimeException exception) {
            return QualificationResult.failed(
                    "Could not generate valid qualification tests: " + exception.getMessage()
            );
        }

        for (FunctionTestCaseDTO test : tests) {
            if (test.expectedOutput() == null || test.expectedOutput().isBlank()) {
                return QualificationResult.failed(
                        "Automatic qualification only accepts successful expected-output tests."
                );
            }
            FunctionRunResultDTO run;
            try {
                run = functionInvocationService.run(runRequest(draft, test));
            } catch (RuntimeException exception) {
                return QualificationResult.failed(
                        "Judge0 could not validate test '" + test.name() + "': "
                                + exception.getMessage()
                );
            }
            if (run.status() != FunctionInvocationStatus.SUCCEEDED) {
                return QualificationResult.failed(
                        "Judge0 rejected test '" + test.name() + "': "
                                + firstText(
                                run.errorMessage(),
                                run.compileOutput(),
                                run.stderr(),
                                run.message(),
                                run.judge0StatusDescription(),
                                "execution failed"
                        )
                );
            }
            JsonNode expected = readJson(test.expectedOutput(), "expectedOutput");
            if (!expected.equals(run.output())) {
                return QualificationResult.failed(
                        "Test '" + test.name() + "' returned "
                                + json(run.output()) + " instead of " + json(expected) + "."
                );
            }
        }

        FunctionVersionRequestDTO metadata = metadataWithTests(draft, tests);
        functionRegistryService.updateVersionMetadata(
                draft.functionId(),
                draft.version(),
                metadata
        );
        functionRegistryService.publishVersion(draft.functionId(), draft.version());
        return QualificationResult.qualified(
                "voyager://function/" + resourceName + "@v" + draft.version()
        );
    }

    private List<FunctionTestCaseDTO> generateTests(
            WorkflowAiProposedFunctionDTO proposal,
            AiModelConfig modelConfig
    ) {
        ChatModel model = modelResolver.resolve(modelConfig);
        ChatResponse response = model.chat(List.of(
                SystemMessage.from("""
                        Generate basic qualification examples for a deterministic local function.
                        Derive expected results from the described behavior, not by executing or
                        imitating the implementation. Return strict JSON only:
                        {"testCases":[{"name":"...","input":"<JSON string>",
                        "expectedOutput":"<JSON string>","expectedError":null}]}
                        Generate 1 to 5 successful cases. Include a normal case and, when meaningful,
                        a successful boundary case. input and expectedOutput must each contain exactly
                        one valid JSON value. Do not generate expectedError cases.
                        """),
                UserMessage.from(
                        "Description:\n" + nullToEmpty(proposal.description())
                                + "\nRationale:\n" + nullToEmpty(proposal.rationale())
                                + "\nSource code (context only; do not copy its observed output):\n"
                                + proposal.sourceCode()
                )
        ));
        String raw = response.aiMessage() == null ? null : response.aiMessage().text();
        JsonNode root = objectMapper.readTree(extractJson(raw));
        JsonNode cases = root == null ? null : root.get("testCases");
        if (cases == null || !cases.isArray()) {
            throw new IllegalArgumentException("response does not contain a testCases array");
        }
        List<FunctionTestCaseDTO> result = new ArrayList<>();
        for (JsonNode node : cases) {
            result.add(objectMapper.treeToValue(node, FunctionTestCaseDTO.class));
        }
        return List.copyOf(result);
    }

    private FunctionRunRequestDTO runRequest(
            FunctionVersionResponseDTO draft,
            FunctionTestCaseDTO test
    ) {
        return new FunctionRunRequestDTO(
                draft.languageId(),
                draft.sourceMode(),
                draft.sourceCode(),
                draft.additionalFilesBase64(),
                draft.compilerOptions(),
                draft.commandLineArguments(),
                draft.cpuTimeLimitSeconds(),
                draft.wallTimeLimitSeconds(),
                draft.memoryLimitKb(),
                draft.maxFileSizeKb(),
                draft.maxOutputBytes(),
                draft.enableNetwork(),
                readJson(test.input(), "input")
        );
    }

    private FunctionVersionRequestDTO metadataWithTests(
            FunctionVersionResponseDTO draft,
            List<FunctionTestCaseDTO> tests
    ) {
        return new FunctionVersionRequestDTO(
                draft.sourceMode(),
                draft.languageId(),
                draft.sourceCode(),
                draft.additionalFilesBase64(),
                draft.compilerOptions(),
                draft.commandLineArguments(),
                draft.cpuTimeLimitSeconds(),
                draft.wallTimeLimitSeconds(),
                draft.memoryLimitKb(),
                draft.maxFileSizeKb(),
                draft.maxOutputBytes(),
                draft.enableNetwork(),
                draft.note(),
                tests,
                draft.status()
        );
    }

    private JsonNode readJson(String value, String field) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(field + " is not valid JSON", exception);
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("model returned an empty response");
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("model response is not JSON");
        }
        return trimmed.substring(start, end + 1);
    }

    private String json(JsonNode value) {
        return value == null ? "null" : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "execution failed";
    }

    public record QualificationResult(boolean qualified, String resourceUri, String reason) {
        public static QualificationResult qualified(String resourceUri) {
            return new QualificationResult(true, resourceUri, null);
        }

        public static QualificationResult failed(String reason) {
            return new QualificationResult(false, null, reason);
        }
    }
}
