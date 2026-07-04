package com.job.scheduler.controller;

import com.job.scheduler.dto.FunctionDefinitionRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionResponseDTO;
import com.job.scheduler.dto.FunctionInvocationResponseDTO;
import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.FunctionTestInvocationRequestDTO;
import com.job.scheduler.dto.FunctionVersionRequestDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
import com.job.scheduler.dto.Judge0RuntimeInfoDTO;
import com.job.scheduler.service.FunctionInvocationService;
import com.job.scheduler.service.FunctionRegistryService;
import com.job.scheduler.service.Judge0Client;
import com.job.scheduler.service.Judge0RuntimeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/app/v1/functions")
public class FunctionController {
    private final FunctionRegistryService functionRegistryService;
    private final FunctionInvocationService functionInvocationService;
    private final Judge0Client judge0Client;
    private final Judge0RuntimeService judge0RuntimeService;

    @GetMapping("/languages")
    public ResponseEntity<List<FunctionLanguageDTO>> getLanguages() {
        return ResponseEntity.ok(judge0Client.listLanguages());
    }

    @GetMapping("/runtime")
    public ResponseEntity<Judge0RuntimeInfoDTO> getRuntimeInfo() {
        return ResponseEntity.ok(judge0RuntimeService.runtimeInfo());
    }

    @PostMapping
    public ResponseEntity<FunctionDefinitionResponseDTO> createFunction(
            @Valid @RequestBody FunctionDefinitionRequestDTO request
    ) {
        return ResponseEntity.ok(functionRegistryService.createFunction(request));
    }

    @GetMapping
    public ResponseEntity<List<FunctionDefinitionResponseDTO>> getFunctions() {
        return ResponseEntity.ok(functionRegistryService.getFunctions());
    }

    @GetMapping("/{functionId}")
    public ResponseEntity<FunctionDefinitionResponseDTO> getFunction(
            @PathVariable UUID functionId
    ) {
        return ResponseEntity.ok(functionRegistryService.getFunction(functionId));
    }

    @PutMapping("/{functionId}")
    public ResponseEntity<FunctionDefinitionResponseDTO> updateFunction(
            @PathVariable UUID functionId,
            @Valid @RequestBody FunctionDefinitionRequestDTO request
    ) {
        return ResponseEntity.ok(
                functionRegistryService.updateFunction(functionId, request)
        );
    }

    @PostMapping("/{functionId}/versions")
    public ResponseEntity<FunctionVersionResponseDTO> createVersion(
            @PathVariable UUID functionId,
            @Valid @RequestBody FunctionVersionRequestDTO request
    ) {
        return ResponseEntity.ok(
                functionRegistryService.createVersion(functionId, request)
        );
    }

    @GetMapping("/{functionId}/versions")
    public ResponseEntity<List<FunctionVersionResponseDTO>> getVersions(
            @PathVariable UUID functionId
    ) {
        return ResponseEntity.ok(functionRegistryService.getVersions(functionId));
    }

    @PostMapping("/{functionId}/versions/{version}/activate")
    public ResponseEntity<FunctionDefinitionResponseDTO> activateVersion(
            @PathVariable UUID functionId,
            @PathVariable @Min(1) int version
    ) {
        return ResponseEntity.ok(
                functionRegistryService.activateVersion(functionId, version)
        );
    }

    @PostMapping("/{functionId}/test-invocations")
    public ResponseEntity<FunctionInvocationResponseDTO> testInvoke(
            @PathVariable UUID functionId,
            @Valid @RequestBody FunctionTestInvocationRequestDTO request
    ) {
        return ResponseEntity.ok(
                functionInvocationService.testInvoke(functionId, request)
        );
    }

    @GetMapping("/{functionId}/invocations")
    public ResponseEntity<List<FunctionInvocationResponseDTO>> getInvocations(
            @PathVariable UUID functionId
    ) {
        return ResponseEntity.ok(
                functionInvocationService.getInvocations(functionId)
        );
    }
}
