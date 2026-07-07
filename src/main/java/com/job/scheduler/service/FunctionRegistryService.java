package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionDefinitionRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionResponseDTO;
import com.job.scheduler.dto.FunctionVersionRequestDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FunctionRegistryService {
    private static final String FUNCTION_NOT_FOUND_MESSAGE =
            "Function does not exist";
    private static final String FUNCTION_VERSION_NOT_FOUND_MESSAGE =
            "Function version does not exist";

    private final FunctionDefinitionRepository functionRepository;
    private final FunctionVersionRepository versionRepository;

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

    // Comma-separated Judge0 language ids permitted for new versions. Blank means
    // no allowlist (any language the Judge0 image provides is accepted).
    @Value("${scheduler.judge0.allowed-language-ids:}")
    private String allowedLanguageIdsProperty;

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

    public List<FunctionDefinitionResponseDTO> getFunctions() {
        return functionRepository.findAllByOrderByUpdatedAtDesc()
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
    public FunctionVersionResponseDTO createVersion(
            UUID functionId,
            FunctionVersionRequestDTO request
    ) {
        FunctionDefinition function = findFunction(functionId);
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
        version.setStatus(status);

        FunctionVersion saved = versionRepository.save(version);
        if (status == FunctionVersionStatus.AVAILABLE
                && function.getActiveVersion() == null) {
            function.setActiveVersion(saved.getVersion());
            functionRepository.save(function);
        }
        return toResponse(saved);
    }

    public List<FunctionVersionResponseDTO> getVersions(UUID functionId) {
        FunctionDefinition function = findFunction(functionId);
        return versionRepository.findByFunctionDefinitionOrderByVersionDesc(function)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public FunctionDefinitionResponseDTO activateVersion(
            UUID functionId,
            int version
    ) {
        FunctionDefinition function = findFunction(functionId);
        FunctionVersion functionVersion = findVersion(function, version);
        if (functionVersion.getStatus() != FunctionVersionStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Function version is not available: " + version
            );
        }
        function.setActiveVersion(version);
        return toResponse(functionRepository.save(function));
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
                version.getCompilerOptions(),
                version.getCommandLineArguments(),
                version.getCpuTimeLimitSeconds(),
                version.getWallTimeLimitSeconds(),
                version.getMemoryLimitKb(),
                version.getMaxFileSizeKb(),
                version.getMaxOutputBytes(),
                version.isEnableNetwork(),
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
        assertLanguageAllowed(request.languageId());
        FunctionSourceMode mode = sourceMode(request);
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

    private void assertLanguageAllowed(Integer languageId) {
        Set<Integer> allowed = allowedLanguageIds();
        if (!allowed.isEmpty() && !allowed.contains(languageId)) {
            throw new IllegalArgumentException(
                    "Language " + languageId
                            + " is not allowed. Allowed language ids: " + allowed
            );
        }
    }

    private Set<Integer> allowedLanguageIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        if (allowedLanguageIdsProperty == null
                || allowedLanguageIdsProperty.isBlank()) {
            return ids;
        }
        for (String token : allowedLanguageIdsProperty.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // Skip malformed entries so one typo doesn't disable execution.
            }
        }
        return ids;
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
}
