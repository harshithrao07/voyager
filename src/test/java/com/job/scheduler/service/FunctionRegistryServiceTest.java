package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionVersionSettingsRequestDTO;
import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionVersion;
import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.FunctionVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionRegistryServiceTest {
    @Mock
    private FunctionDefinitionRepository functionRepository;

    @Mock
    private FunctionVersionRepository versionRepository;

    @Mock
    private FunctionRuntimePolicy runtimePolicy;

    private FunctionRegistryService service;

    @BeforeEach
    void setUp() {
        service = new FunctionRegistryService(
                functionRepository,
                versionRepository,
                runtimePolicy
        );
    }

    @Test
    void getFunctionsHidesArchivedFunctionsByDefault() {
        FunctionDefinition function = function(FunctionStatus.ENABLED);
        when(functionRepository.findByStatusNotOrderByUpdatedAtDesc(
                FunctionStatus.ARCHIVED
        )).thenReturn(List.of(function));

        assertThat(service.getFunctions(false))
                .extracting(response -> response.status())
                .containsExactly(FunctionStatus.ENABLED);
    }

    @Test
    void archiveFunctionMarksFunctionArchived() {
        FunctionDefinition function = function(FunctionStatus.ENABLED);
        when(functionRepository.findById(function.getId()))
                .thenReturn(Optional.of(function));
        when(functionRepository.save(any(FunctionDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.archiveFunction(function.getId()).status())
                .isEqualTo(FunctionStatus.ARCHIVED);
        assertThat(function.getStatus()).isEqualTo(FunctionStatus.ARCHIVED);
        verify(functionRepository).save(function);
    }

    @Test
    void updateVersionSettingsPersistsExecutionSettingsOnly() {
        FunctionDefinition function = function(FunctionStatus.ENABLED);
        FunctionVersion version = version(function);
        when(functionRepository.findById(function.getId()))
                .thenReturn(Optional.of(function));
        when(versionRepository.findByFunctionDefinitionAndVersion(function, 1))
                .thenReturn(Optional.of(version));
        when(versionRepository.save(any(FunctionVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateVersionSettings(
                function.getId(),
                1,
                new FunctionVersionSettingsRequestDTO(
                        "-O2",
                        "--fast",
                        3.5,
                        12.0,
                        262144,
                        2048,
                        8192,
                        true
                )
        );

        assertThat(response.compilerOptions()).isEqualTo("-O2");
        assertThat(response.commandLineArguments()).isEqualTo("--fast");
        assertThat(response.cpuTimeLimitSeconds()).isEqualTo(3.5);
        assertThat(response.wallTimeLimitSeconds()).isEqualTo(12.0);
        assertThat(response.memoryLimitKb()).isEqualTo(262144);
        assertThat(response.maxFileSizeKb()).isEqualTo(2048);
        assertThat(response.maxOutputBytes()).isEqualTo(8192);
        assertThat(response.enableNetwork()).isTrue();
        assertThat(version.getSourceCode()).isEqualTo("print('{}')");
    }

    @Test
    void updateVersionSettingsRejectsArchivedFunctions() {
        FunctionDefinition function = function(FunctionStatus.ARCHIVED);
        when(functionRepository.findById(function.getId()))
                .thenReturn(Optional.of(function));

        assertThatThrownBy(() -> service.updateVersionSettings(
                function.getId(),
                1,
                new FunctionVersionSettingsRequestDTO(
                        null,
                        null,
                        2.0,
                        10.0,
                        131072,
                        1024,
                        4096,
                        false
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Archived functions cannot be changed");
    }

    @Test
    void publishVersionMarksDraftAvailableAndSetsActiveWhenMissing() {
        FunctionDefinition function = function(FunctionStatus.ENABLED);
        function.setActiveVersion(null);
        FunctionVersion version = version(function);
        version.setVersion(2);
        version.setStatus(FunctionVersionStatus.DRAFT);
        when(functionRepository.findById(function.getId()))
                .thenReturn(Optional.of(function));
        when(versionRepository.findByFunctionDefinitionAndVersion(function, 2))
                .thenReturn(Optional.of(version));
        when(versionRepository.save(any(FunctionVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(functionRepository.save(any(FunctionDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.publishVersion(function.getId(), 2);

        assertThat(response.status()).isEqualTo(FunctionVersionStatus.AVAILABLE);
        assertThat(version.getStatus()).isEqualTo(FunctionVersionStatus.AVAILABLE);
        assertThat(function.getActiveVersion()).isEqualTo(2);
        verify(runtimePolicy).assertLanguageSupported(71);
        verify(versionRepository).save(version);
        verify(functionRepository).save(function);
    }

    @Test
    void publishVersionDoesNotReplaceExistingActiveVersion() {
        FunctionDefinition function = function(FunctionStatus.ENABLED);
        FunctionVersion version = version(function);
        version.setVersion(2);
        version.setStatus(FunctionVersionStatus.DRAFT);
        when(functionRepository.findById(function.getId()))
                .thenReturn(Optional.of(function));
        when(versionRepository.findByFunctionDefinitionAndVersion(function, 2))
                .thenReturn(Optional.of(version));
        when(versionRepository.save(any(FunctionVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.publishVersion(function.getId(), 2);

        assertThat(function.getActiveVersion()).isEqualTo(1);
        assertThat(version.getStatus()).isEqualTo(FunctionVersionStatus.AVAILABLE);
    }

    @Test
    void publishVersionRejectsIncompleteSingleFileDraft() {
        FunctionDefinition function = function(FunctionStatus.ENABLED);
        FunctionVersion version = version(function);
        version.setStatus(FunctionVersionStatus.DRAFT);
        version.setSourceCode(null);
        when(functionRepository.findById(function.getId()))
                .thenReturn(Optional.of(function));
        when(versionRepository.findByFunctionDefinitionAndVersion(function, 1))
                .thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.publishVersion(function.getId(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceCode is required");
    }

    private FunctionDefinition function(FunctionStatus status) {
        FunctionDefinition value = new FunctionDefinition();
        value.setId(UUID.randomUUID());
        value.setNamespace("billing");
        value.setName("tax");
        value.setActiveVersion(1);
        value.setStatus(status);
        return value;
    }

    private FunctionVersion version(FunctionDefinition functionDefinition) {
        FunctionVersion value = new FunctionVersion();
        value.setId(UUID.randomUUID());
        value.setFunctionDefinition(functionDefinition);
        value.setVersion(1);
        value.setSourceMode(FunctionSourceMode.SINGLE_FILE);
        value.setLanguageId(71);
        value.setSourceCode("print('{}')");
        value.setCpuTimeLimitSeconds(2.0);
        value.setWallTimeLimitSeconds(10.0);
        value.setMemoryLimitKb(131072);
        value.setMaxFileSizeKb(1024);
        value.setMaxOutputBytes(4096);
        value.setStatus(FunctionVersionStatus.AVAILABLE);
        return value;
    }
}
