package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.enums.FunctionSourceMode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunctionRuntimePolicyTest {
    private final Judge0Client judge0Client = mock(Judge0Client.class);
    private final FunctionRuntimePolicy policy =
            new FunctionRuntimePolicy(judge0Client);

    @Test
    void defaultPolicyKeepsWorkflowSafeStdioRuntimes() {
        List<FunctionLanguageDTO> supported = policy.supportedLanguages(List.of(
                new FunctionLanguageDTO(71, "Python (3.8.1)", true),
                new FunctionLanguageDTO(63, "JavaScript (Node.js 12.14.0)", true),
                new FunctionLanguageDTO(74, "TypeScript (3.7.4)", true),
                new FunctionLanguageDTO(62, "Java (OpenJDK 13.0.1)", true),
                new FunctionLanguageDTO(54, "C++ (GCC 9.2.0)", true),
                new FunctionLanguageDTO(72, "Ruby (2.7.0)", true),
                new FunctionLanguageDTO(60, "Go (1.13.5)", false),
                new FunctionLanguageDTO(73, "Rust (1.40.0)", true),
                new FunctionLanguageDTO(82, "SQL (SQLite 3.27.2)", false),
                new FunctionLanguageDTO(89, "Multi-file program", false)
        ));

        assertThat(supported)
                .extracting(FunctionLanguageDTO::id)
                .containsExactly(71, 63, 74, 62, 54, 72, 60, 73);
    }

    @Test
    void defaultPolicyRejectsNonFunctionRuntimes() {
        when(judge0Client.languageName(82))
                .thenReturn("SQL (SQLite 3.27.2)");
        when(judge0Client.languageName(89))
                .thenReturn("Multi-file program");

        assertThatThrownBy(() -> policy.assertLanguageSupported(82))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supported runtimes");
        assertThatThrownBy(() -> policy.assertLanguageSupported(89))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supported runtimes");
    }

    @Test
    void multiFilePolicyRequiresLanguageWithMultiFileRecipe() {
        when(judge0Client.languageName(71))
                .thenReturn("Python (3.8.1)");
        when(judge0Client.languageName(60))
                .thenReturn("Go (1.13.5)");

        policy.assertLanguageSupported(71, FunctionSourceMode.MULTI_FILE);

        assertThatThrownBy(() -> policy.assertLanguageSupported(
                60,
                FunctionSourceMode.MULTI_FILE
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MULTI_FILE");
    }

    @Test
    void configuredIdsOverrideDefaultFamilies() {
        ReflectionTestUtils.setField(
                policy,
                "allowedLanguageIdsProperty",
                "54, 71"
        );

        List<FunctionLanguageDTO> supported = policy.supportedLanguages(List.of(
                new FunctionLanguageDTO(54, "C++ (GCC 9.2.0)", true),
                new FunctionLanguageDTO(71, "Python (3.8.1)", true),
                new FunctionLanguageDTO(63, "JavaScript (Node.js 12.14.0)", true),
                new FunctionLanguageDTO(72, "Ruby (2.7.0)", true)
        ));

        assertThat(supported)
                .extracting(FunctionLanguageDTO::id)
                .containsExactly(54, 71);
        policy.assertLanguageSupported(54);
        assertThatThrownBy(() -> policy.assertLanguageSupported(63))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Allowed language ids");
    }

    @Test
    void aiDefaultPrefersConfiguredPythonRuntime() {
        when(judge0Client.listSelectableLanguages()).thenReturn(List.of(
                new FunctionLanguageDTO(63, "JavaScript (Node.js 12.14.0)", true),
                new FunctionLanguageDTO(71, "Python (3.8.1)", true)
        ));
        ReflectionTestUtils.setField(policy, "aiDefaultLanguageId", 71);

        assertThat(policy.aiDefaultLanguage())
                .extracting(FunctionLanguageDTO::id)
                .isEqualTo(71);
    }
}
