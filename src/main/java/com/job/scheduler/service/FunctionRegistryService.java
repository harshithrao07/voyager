package com.job.scheduler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job.scheduler.dto.FunctionDefinitionRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionResponseDTO;
import com.job.scheduler.dto.FunctionSourceFileDTO;
import com.job.scheduler.dto.FunctionTestCaseDTO;
import com.job.scheduler.dto.FunctionVersionRequestDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
import com.job.scheduler.dto.FunctionVersionSettingsRequestDTO;
import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionVersion;
import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.FunctionVersionRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class FunctionRegistryService {
    private static final String FUNCTION_NOT_FOUND_MESSAGE =
            "Function does not exist";
    private static final String FUNCTION_VERSION_NOT_FOUND_MESSAGE =
            "Function version does not exist";
    private static final ObjectMapper TEST_CASE_MAPPER = new ObjectMapper();
    private static final TypeReference<List<FunctionTestCaseDTO>> TEST_CASE_LIST_TYPE =
            new TypeReference<>() {
            };

    private final FunctionDefinitionRepository functionRepository;
    private final FunctionVersionRepository versionRepository;
    private final FunctionRuntimePolicy runtimePolicy;

    @Value("${scheduler.judge0.default-cpu-time-limit-seconds:2.0}")
    private double defaultCpuTimeLimitSeconds;

    @Value("${scheduler.judge0.default-wall-time-limit-seconds:10.0}")
    private double defaultWallTimeLimitSeconds;

    @Value("${scheduler.judge0.default-memory-limit-kb:131072}")
    private int defaultMemoryLimitKb;

    @Value("${scheduler.judge0.default-max-file-size-kb:1024}")
    private int defaultMaxFileSizeKb;

    @Value("${scheduler.judge0.default-max-output-bytes:65536}")
    private int defaultMaxOutputBytes;

    @Transactional
    public FunctionDefinitionResponseDTO createFunction(
            FunctionDefinitionRequestDTO request
    ) {
        if (functionRepository.existsByNamespaceAndName(
                request.namespace(),
                request.name()
        )) {
            throw new IllegalStateException(
                    "Function already exists: "
                            + request.namespace() + "/" + request.name()
            );
        }

        FunctionDefinition function = new FunctionDefinition();
        function.setNamespace(request.namespace());
        function.setName(request.name());
        applyRequest(function, request);
        return toResponse(functionRepository.save(function));
    }

    public List<FunctionDefinitionResponseDTO> getFunctions(
            boolean includeArchived
    ) {
        List<FunctionDefinition> functions = includeArchived
                ? functionRepository.findAllByOrderByUpdatedAtDesc()
                : functionRepository.findByStatusNotOrderByUpdatedAtDesc(
                        FunctionStatus.ARCHIVED
                );
        return functions
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public FunctionDefinitionResponseDTO getFunction(UUID functionId) {
        return toResponse(findFunction(functionId));
    }

    @Transactional
    public FunctionDefinitionResponseDTO updateFunction(
            UUID functionId,
            FunctionDefinitionRequestDTO request
    ) {
        FunctionDefinition function = findFunction(functionId);
        if (!function.getNamespace().equals(request.namespace())
                || !function.getName().equals(request.name())) {
            throw new IllegalArgumentException(
                    "Function namespace/name cannot be changed"
            );
        }
        applyRequest(function, request);
        return toResponse(functionRepository.save(function));
    }

    @Transactional
    public FunctionDefinitionResponseDTO archiveFunction(UUID functionId) {
        FunctionDefinition function = findFunction(functionId);
        if (function.getStatus() == FunctionStatus.ARCHIVED) {
            return toResponse(function);
        }
        function.setStatus(FunctionStatus.ARCHIVED);
        return toResponse(functionRepository.save(function));
    }

    @Transactional
    public FunctionVersionResponseDTO createVersion(
            UUID functionId,
            FunctionVersionRequestDTO request
    ) {
        FunctionDefinition function = findFunction(functionId);
        ensureFunctionCanChangeVersions(function);
        FunctionVersionStatus status = versionStatus(request);
        validateVersionRequest(request, status);

        FunctionVersion version = new FunctionVersion();
        version.setFunctionDefinition(function);
        version.setVersion((int) versionRepository.countByFunctionDefinition(function) + 1);
        version.setSourceMode(sourceMode(request));
        version.setLanguageId(request.languageId());
        version.setSourceCode(blankToNull(request.sourceCode()));
        version.setAdditionalFilesBase64(blankToNull(request.additionalFilesBase64()));
        version.setCompilerOptions(blankToNull(request.compilerOptions()));
        version.setCommandLineArguments(blankToNull(request.commandLineArguments()));
        version.setCpuTimeLimitSeconds(defaulted(
                request.cpuTimeLimitSeconds(),
                defaultCpuTimeLimitSeconds
        ));
        version.setWallTimeLimitSeconds(defaulted(
                request.wallTimeLimitSeconds(),
                defaultWallTimeLimitSeconds
        ));
        version.setMemoryLimitKb(defaulted(
                request.memoryLimitKb(),
                defaultMemoryLimitKb
        ));
        version.setMaxFileSizeKb(defaulted(
                request.maxFileSizeKb(),
                defaultMaxFileSizeKb
        ));
        version.setMaxOutputBytes(defaulted(
                request.maxOutputBytes(),
                defaultMaxOutputBytes
        ));
        version.setEnableNetwork(Boolean.TRUE.equals(request.enableNetwork()));
        version.setNote(blankToNull(request.note()));
        version.setTestCases(serializeTestCases(request.testCases()));
        version.setStatus(status);

        FunctionVersion saved = versionRepository.save(version);
        if (status == FunctionVersionStatus.AVAILABLE
                && function.getActiveVersion() == null) {
            function.setActiveVersion(saved.getVersion());
            functionRepository.save(function);
        }
        return toResponse(saved);
    }

    @Transactional
    public FunctionVersionResponseDTO updateVersion(
            UUID functionId,
            int version,
            FunctionVersionRequestDTO request
    ) {
        FunctionDefinition function = findFunction(functionId);
        ensureFunctionCanChangeVersions(function);
        FunctionVersion functionVersion = findVersion(function, version);
        // Only drafts are mutable: published versions stay immutable so workflows
        // pinned to them and the invocation history keep matching their code.
        if (functionVersion.getStatus() != FunctionVersionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only draft versions can be edited in place"
            );
        }
        validateVersionRequest(request, FunctionVersionStatus.DRAFT);

        functionVersion.setSourceMode(sourceMode(request));
        functionVersion.setLanguageId(request.languageId());
        functionVersion.setSourceCode(blankToNull(request.sourceCode()));
        functionVersion.setAdditionalFilesBase64(blankToNull(
                request.additionalFilesBase64()
        ));
        functionVersion.setCompilerOptions(blankToNull(request.compilerOptions()));
        functionVersion.setCommandLineArguments(blankToNull(
                request.commandLineArguments()
        ));
        functionVersion.setCpuTimeLimitSeconds(defaulted(
                request.cpuTimeLimitSeconds(),
                defaultCpuTimeLimitSeconds
        ));
        functionVersion.setWallTimeLimitSeconds(defaulted(
                request.wallTimeLimitSeconds(),
                defaultWallTimeLimitSeconds
        ));
        functionVersion.setMemoryLimitKb(defaulted(
                request.memoryLimitKb(),
                defaultMemoryLimitKb
        ));
        functionVersion.setMaxFileSizeKb(defaulted(
                request.maxFileSizeKb(),
                defaultMaxFileSizeKb
        ));
        functionVersion.setMaxOutputBytes(defaulted(
                request.maxOutputBytes(),
                defaultMaxOutputBytes
        ));
        functionVersion.setEnableNetwork(Boolean.TRUE.equals(
                request.enableNetwork()
        ));
        functionVersion.setNote(blankToNull(request.note()));
        functionVersion.setTestCases(serializeTestCases(request.testCases()));
        return toResponse(versionRepository.save(functionVersion));
    }

    // In-place update of a version's metadata: note, execution settings, and
    // saved test cases. Code, language, source mode, and status are deliberately
    // ignored so this can never change what the version runs — those changes
    // must go through a new version instead.
    @Transactional
    public FunctionVersionResponseDTO updateVersionMetadata(
            UUID functionId,
            int version,
            FunctionVersionRequestDTO request
    ) {
        FunctionDefinition function = findFunction(functionId);
        ensureFunctionCanChangeVersions(function);
        FunctionVersion functionVersion = findVersion(function, version);
        if (functionVersion.getStatus() == FunctionVersionStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Archived function versions cannot be changed"
            );
        }
        functionVersion.setCompilerOptions(blankToNull(request.compilerOptions()));
        functionVersion.setCommandLineArguments(blankToNull(
                request.commandLineArguments()
        ));
        functionVersion.setCpuTimeLimitSeconds(defaulted(
                request.cpuTimeLimitSeconds(),
                defaultCpuTimeLimitSeconds
        ));
        functionVersion.setWallTimeLimitSeconds(defaulted(
                request.wallTimeLimitSeconds(),
                defaultWallTimeLimitSeconds
        ));
        functionVersion.setMemoryLimitKb(defaulted(
                request.memoryLimitKb(),
                defaultMemoryLimitKb
        ));
        functionVersion.setMaxFileSizeKb(defaulted(
                request.maxFileSizeKb(),
                defaultMaxFileSizeKb
        ));
        functionVersion.setMaxOutputBytes(defaulted(
                request.maxOutputBytes(),
                defaultMaxOutputBytes
        ));
        functionVersion.setEnableNetwork(Boolean.TRUE.equals(
                request.enableNetwork()
        ));
        functionVersion.setNote(blankToNull(request.note()));
        functionVersion.setTestCases(serializeTestCases(request.testCases()));
        return toResponse(versionRepository.save(functionVersion));
    }

    public List<FunctionVersionResponseDTO> getVersions(UUID functionId) {
        FunctionDefinition function = findFunction(functionId);
        return versionRepository.findByFunctionDefinitionOrderByVersionDesc(function)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public FunctionVersionResponseDTO publishVersion(
            UUID functionId,
            int version
    ) {
        FunctionDefinition function = findFunction(functionId);
        ensureFunctionCanChangeVersions(function);
        FunctionVersion functionVersion = findVersion(function, version);
        if (functionVersion.getStatus() == FunctionVersionStatus.AVAILABLE) {
            return toResponse(functionVersion);
        }
        if (functionVersion.getStatus() != FunctionVersionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Function version cannot be published: " + version
            );
        }
        validateVersionReady(functionVersion);
        functionVersion.setStatus(FunctionVersionStatus.AVAILABLE);
        FunctionVersion saved = versionRepository.save(functionVersion);
        if (function.getActiveVersion() == null) {
            function.setActiveVersion(saved.getVersion());
            functionRepository.save(function);
        }
        return toResponse(saved);
    }

    @Transactional
    public FunctionDefinitionResponseDTO activateVersion(
            UUID functionId,
            int version
    ) {
        FunctionDefinition function = findFunction(functionId);
        ensureFunctionCanChangeVersions(function);
        FunctionVersion functionVersion = findVersion(function, version);
        if (functionVersion.getStatus() != FunctionVersionStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Function version is not available: " + version
            );
        }
        function.setActiveVersion(version);
        return toResponse(functionRepository.save(function));
    }

    @Transactional
    public FunctionVersionResponseDTO updateVersionSettings(
            UUID functionId,
            int version,
            FunctionVersionSettingsRequestDTO request
    ) {
        FunctionDefinition function = findFunction(functionId);
        ensureFunctionCanChangeVersions(function);
        FunctionVersion functionVersion = findVersion(function, version);
        if (functionVersion.getStatus() == FunctionVersionStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Archived function versions cannot be changed"
            );
        }
        functionVersion.setCompilerOptions(blankToNull(request.compilerOptions()));
        functionVersion.setCommandLineArguments(blankToNull(
                request.commandLineArguments()
        ));
        functionVersion.setCpuTimeLimitSeconds(request.cpuTimeLimitSeconds());
        functionVersion.setWallTimeLimitSeconds(request.wallTimeLimitSeconds());
        functionVersion.setMemoryLimitKb(request.memoryLimitKb());
        functionVersion.setMaxFileSizeKb(request.maxFileSizeKb());
        functionVersion.setMaxOutputBytes(request.maxOutputBytes());
        functionVersion.setEnableNetwork(Boolean.TRUE.equals(
                request.enableNetwork()
        ));
        return toResponse(versionRepository.save(functionVersion));
    }

    FunctionDefinition findFunction(UUID functionId) {
        return functionRepository.findById(functionId)
                .orElseThrow(() -> new EntityNotFoundException(
                        FUNCTION_NOT_FOUND_MESSAGE
                ));
    }

    FunctionDefinition findFunction(String namespace, String name) {
        return functionRepository.findByNamespaceAndName(namespace, name)
                .orElseThrow(() -> new EntityNotFoundException(
                        FUNCTION_NOT_FOUND_MESSAGE
                ));
    }

    FunctionVersion findVersion(FunctionDefinition function, int version) {
        return versionRepository.findByFunctionDefinitionAndVersion(
                        function,
                        version
                )
                .orElseThrow(() -> new EntityNotFoundException(
                        FUNCTION_VERSION_NOT_FOUND_MESSAGE
                ));
    }

    FunctionVersion activeVersion(FunctionDefinition function) {
        if (function.getActiveVersion() == null) {
            throw new IllegalStateException("Function has no active version");
        }
        return findVersion(function, function.getActiveVersion());
    }

    FunctionDefinitionResponseDTO toResponse(FunctionDefinition function) {
        return new FunctionDefinitionResponseDTO(
                function.getId(),
                function.getNamespace(),
                function.getName(),
                function.getDescription(),
                function.getActiveVersion(),
                function.getStatus(),
                function.getCreatedAt(),
                function.getUpdatedAt()
        );
    }

    FunctionVersionResponseDTO toResponse(FunctionVersion version) {
        return new FunctionVersionResponseDTO(
                version.getId(),
                version.getFunctionDefinition().getId(),
                version.getVersion(),
                version.getSourceMode(),
                version.getLanguageId(),
                version.getSourceCode() != null,
                version.getAdditionalFilesBase64() != null,
                version.getSourceCode(),
                version.getAdditionalFilesBase64(),
                sourceFiles(version.getAdditionalFilesBase64()),
                version.getCompilerOptions(),
                version.getCommandLineArguments(),
                version.getCpuTimeLimitSeconds(),
                version.getWallTimeLimitSeconds(),
                version.getMemoryLimitKb(),
                version.getMaxFileSizeKb(),
                version.getMaxOutputBytes(),
                version.isEnableNetwork(),
                version.getNote(),
                deserializeTestCases(version.getTestCases()),
                version.getStatus(),
                version.getCreatedAt(),
                version.getUpdatedAt()
        );
    }

    private void applyRequest(
            FunctionDefinition function,
            FunctionDefinitionRequestDTO request
    ) {
        function.setDescription(blankToNull(request.description()));
        function.setStatus(request.status() == null
                ? FunctionStatus.ENABLED
                : request.status());
    }

    private void validateVersionRequest(
            FunctionVersionRequestDTO request,
            FunctionVersionStatus status
    ) {
        FunctionSourceMode mode = sourceMode(request);
        runtimePolicy.assertLanguageSupported(request.languageId(), mode);
        if (status != FunctionVersionStatus.DRAFT) {
            if (mode == FunctionSourceMode.SINGLE_FILE
                    && blankToNull(request.sourceCode()) == null) {
                throw new IllegalArgumentException(
                        "sourceCode is required for SINGLE_FILE functions"
                );
            }
            if (mode == FunctionSourceMode.MULTI_FILE
                    && blankToNull(request.additionalFilesBase64()) == null) {
                throw new IllegalArgumentException(
                        "additionalFilesBase64 is required for MULTI_FILE functions"
                );
            }
        }
        if (mode == FunctionSourceMode.SINGLE_FILE
                && blankToNull(request.additionalFilesBase64()) != null) {
            throw new IllegalArgumentException(
                    "additionalFilesBase64 is only valid for MULTI_FILE functions"
            );
        }
        if (mode == FunctionSourceMode.MULTI_FILE
                && blankToNull(request.sourceCode()) != null) {
            throw new IllegalArgumentException(
                    "sourceCode is not valid for MULTI_FILE functions"
            );
        }
    }

    private void validateVersionReady(FunctionVersion version) {
        runtimePolicy.assertLanguageSupported(
                version.getLanguageId(),
                version.getSourceMode()
        );
        if (version.getSourceMode() == FunctionSourceMode.SINGLE_FILE
                && blankToNull(version.getSourceCode()) == null) {
            throw new IllegalArgumentException(
                    "sourceCode is required for SINGLE_FILE functions"
            );
        }
        if (version.getSourceMode() == FunctionSourceMode.MULTI_FILE
                && blankToNull(version.getAdditionalFilesBase64()) == null) {
            throw new IllegalArgumentException(
                    "additionalFilesBase64 is required for MULTI_FILE functions"
            );
        }
    }

    private void ensureFunctionCanChangeVersions(FunctionDefinition function) {
        if (function.getStatus() == FunctionStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Archived functions cannot be changed"
            );
        }
    }

    private FunctionVersionStatus versionStatus(FunctionVersionRequestDTO request) {
        FunctionVersionStatus status = request.status() == null
                ? FunctionVersionStatus.AVAILABLE
                : request.status();
        if (status == FunctionVersionStatus.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Archived function versions cannot be created directly"
            );
        }
        return status;
    }

    private FunctionSourceMode sourceMode(FunctionVersionRequestDTO request) {
        return request.sourceMode() == null
                ? FunctionSourceMode.SINGLE_FILE
                : request.sourceMode();
    }

    private double defaulted(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private int defaulted(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String serializeTestCases(List<FunctionTestCaseDTO> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            return null;
        }
        validateTestCases(testCases);
        try {
            return TEST_CASE_MAPPER.writeValueAsString(testCases);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid test cases", e);
        }
    }

    private void validateTestCases(List<FunctionTestCaseDTO> testCases) {
        for (FunctionTestCaseDTO testCase : testCases) {
            String caseName = blankToNull(testCase.name());
            String label = caseName == null ? "Test case" : caseName;
            requireJson(label, "input", testCase.input(), false);
            requireJson(label, "expected output", testCase.expectedOutput(), true);
        }
    }

    private void requireJson(
            String caseName,
            String fieldName,
            String value,
            boolean allowBlank
    ) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            if (allowBlank) {
                return;
            }
            throw new IllegalArgumentException(
                    caseName + ": " + fieldName + " must be valid JSON"
            );
        }
        try {
            TEST_CASE_MAPPER.readTree(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    caseName + ": " + fieldName + " must be valid JSON",
                    e
            );
        }
    }

    private List<FunctionTestCaseDTO> deserializeTestCases(String testCases) {
        String normalized = blankToNull(testCases);
        if (normalized == null) {
            return List.of();
        }
        try {
            return TEST_CASE_MAPPER.readValue(normalized, TEST_CASE_LIST_TYPE);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<FunctionSourceFileDTO> sourceFiles(String additionalFilesBase64) {
        String normalized = blankToNull(additionalFilesBase64);
        if (normalized == null) {
            return List.of();
        }

        List<FunctionSourceFileDTO> files = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(
                Base64.getDecoder().decode(normalized)
        ))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                zip.transferTo(output);
                files.add(new FunctionSourceFileDTO(
                        entry.getName(),
                        output.toString(StandardCharsets.UTF_8)
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return files;
    }
}
