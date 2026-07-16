package com.job.scheduler.workflow.asl.validation;

import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionVersion;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.FunctionVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AslFunctionResourceValidatorTest {
    @Mock
    private FunctionDefinitionRepository functionDefinitionRepository;
    @Mock
    private FunctionVersionRepository functionVersionRepository;

    @InjectMocks
    private AslFunctionResourceValidator validator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<AslValidationIssue> validate(String resource) {
        JsonNode definition = objectMapper.createObjectNode()
                .put("StartAt", "Call")
                .set("States", objectMapper.createObjectNode()
                        .set("Call", objectMapper.createObjectNode()
                                .put("Type", "Task")
                                .put("Resource", resource)
                                .put("End", true)));
        return validator.validate(definition);
    }

    @Test
    void passesWhenPinnedVersionIsAvailable() {
        FunctionDefinition tax = function("tax", FunctionStatus.ENABLED, null);
        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.of(tax));
        when(functionVersionRepository.findByFunctionDefinitionAndVersion(tax, 3))
                .thenReturn(Optional.of(version(3, FunctionVersionStatus.AVAILABLE)));

        assertThat(validate("voyager://function/tax@v3")).isEmpty();
    }

    @Test
    void passesWhenUnpinnedAndActiveVersionIsAvailable() {
        FunctionDefinition tax = function("tax", FunctionStatus.ENABLED, 2);
        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.of(tax));
        when(functionVersionRepository.findByFunctionDefinitionAndVersion(tax, 2))
                .thenReturn(Optional.of(version(2, FunctionVersionStatus.AVAILABLE)));

        assertThat(validate("voyager://function/tax")).isEmpty();
        assertThat(validate("voyager://function/tax@latest")).isEmpty();
    }

    @Test
    void ignoresNonFunctionResources() {
        assertThat(validate("voyager://mcp/crm/get-customer")).isEmpty();
        assertThat(validate("voyager://system/webhook")).isEmpty();
        verifyNoInteractions(functionDefinitionRepository, functionVersionRepository);
    }

    @Test
    void flagsMalformedVersion() {
        List<AslValidationIssue> issues = validate("voyager://function/tax@banana");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_RESOURCE_INVALID"));
    }

    @Test
    void flagsMissingFunctionName() {
        List<AslValidationIssue> issues = validate("voyager://function/@v2");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_RESOURCE_INVALID"));
    }

    @Test
    void flagsUnknownFunction() {
        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.empty());

        List<AslValidationIssue> issues = validate("voyager://function/tax@v3");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_NOT_FOUND"));
    }

    @Test
    void flagsDisabledOrArchivedFunction() {
        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.of(
                        function("tax", FunctionStatus.DISABLED, 1)));
        assertThat(validate("voyager://function/tax@v1")).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_DISABLED"));

        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.of(
                        function("tax", FunctionStatus.ARCHIVED, 1)));
        assertThat(validate("voyager://function/tax@v1")).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_DISABLED"));
    }

    @Test
    void flagsFunctionWithoutActiveVersionForUnpinnedReference() {
        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.of(
                        function("tax", FunctionStatus.ENABLED, null)));

        List<AslValidationIssue> issues = validate("voyager://function/tax");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_NO_ACTIVE_VERSION"));
    }

    @Test
    void flagsUnknownPinnedVersion() {
        FunctionDefinition tax = function("tax", FunctionStatus.ENABLED, null);
        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.of(tax));
        when(functionVersionRepository.findByFunctionDefinitionAndVersion(tax, 9))
                .thenReturn(Optional.empty());

        List<AslValidationIssue> issues = validate("voyager://function/tax@v9");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_VERSION_NOT_FOUND"));
    }

    @Test
    void flagsUnpublishedPinnedVersion() {
        FunctionDefinition tax = function("tax", FunctionStatus.ENABLED, null);
        when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.of(tax));
        when(functionVersionRepository.findByFunctionDefinitionAndVersion(tax, 4))
                .thenReturn(Optional.of(version(4, FunctionVersionStatus.DRAFT)));

        List<AslValidationIssue> issues = validate("voyager://function/tax@v4");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code())
                        .isEqualTo("FUNCTION_VERSION_NOT_AVAILABLE"));
    }

    @Test
    void recursesIntoMapItemProcessor() {
        lenient().when(functionDefinitionRepository.findByName("tax"))
                .thenReturn(Optional.empty());

        JsonNode definition = objectMapper.readTree("""
                {
                  "StartAt": "Each",
                  "States": {
                    "Each": {
                      "Type": "Map",
                      "ItemProcessor": {
                        "StartAt": "Inner",
                        "States": {
                          "Inner": {
                            "Type": "Task",
                            "Resource": "voyager://function/tax@v1",
                            "End": true
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        List<AslValidationIssue> issues = validator.validate(definition);

        assertThat(issues).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.code()).isEqualTo("FUNCTION_NOT_FOUND");
                    assertThat(issue.location()).contains("ItemProcessor");
                });
    }

    private FunctionDefinition function(
            String name,
            FunctionStatus status,
            Integer activeVersion
    ) {
        FunctionDefinition function = new FunctionDefinition();
        function.setName(name);
        function.setStatus(status);
        function.setActiveVersion(activeVersion);
        return function;
    }

    private FunctionVersion version(int number, FunctionVersionStatus status) {
        FunctionVersion functionVersion = new FunctionVersion();
        functionVersion.setVersion(number);
        functionVersion.setStatus(status);
        return functionVersion;
    }
}
