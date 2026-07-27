package com.job.scheduler.controller;

import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.dto.AiModelConfigRequestDTO;
import com.job.scheduler.dto.AiModelDiscoverRequestDTO;
import com.job.scheduler.dto.AiModelEnabledRequestDTO;
import com.job.scheduler.dto.AiModelEvaluationDTO;
import com.job.scheduler.dto.AiModelEvaluationStartRequestDTO;
import com.job.scheduler.dto.AiModelTestRequestDTO;
import com.job.scheduler.dto.AiModelTestResponseDTO;
import com.job.scheduler.service.AiModelConfigService;
import com.job.scheduler.service.AiModelEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/app/v1/ai")
public class AiModelController {
    private final AiModelConfigService aiModelConfigService;
    private final AiModelEvaluationService aiModelEvaluationService;

    @GetMapping("/models")
    public ResponseEntity<List<AiModelConfigDTO>> listModels() {
        return ResponseEntity.ok(aiModelConfigService.listEnabledModels());
    }

    @GetMapping("/models/all")
    public ResponseEntity<List<AiModelConfigDTO>> listAllModels() {
        return ResponseEntity.ok(aiModelConfigService.listAllModels());
    }

    @PostMapping("/models")
    public ResponseEntity<AiModelConfigDTO> createModel(
            @Valid @RequestBody AiModelConfigRequestDTO request
    ) {
        return ResponseEntity.ok(aiModelConfigService.createModel(request));
    }

    @PostMapping("/models/test")
    public ResponseEntity<AiModelTestResponseDTO> testLocalModel(
            @Valid @RequestBody AiModelTestRequestDTO request
    ) {
        return ResponseEntity.ok(aiModelConfigService.testLocalModel(request));
    }

    @PostMapping("/models/discover")
    public ResponseEntity<List<AiModelConfigDTO>> discoverModels(
            @Valid @RequestBody AiModelDiscoverRequestDTO request
    ) {
        return ResponseEntity.ok(aiModelConfigService.discoverAndOnboardModels(
                request.baseUrl(),
                request.credential(),
                request.providerType()
        ));
    }

    @PatchMapping("/models/{modelId}/enabled")
    public ResponseEntity<AiModelConfigDTO> setModelEnabled(
            @PathVariable UUID modelId,
            @RequestBody AiModelEnabledRequestDTO request
    ) {
        return ResponseEntity.ok(aiModelConfigService.setModelEnabled(modelId, request.enabled()));
    }

    @PostMapping("/models/{modelId}/enabled")
    public ResponseEntity<AiModelConfigDTO> setModelEnabledPost(
            @PathVariable UUID modelId,
            @RequestBody AiModelEnabledRequestDTO request
    ) {
        return ResponseEntity.ok(aiModelConfigService.setModelEnabled(modelId, request.enabled()));
    }

    @DeleteMapping("/models/{modelId}")
    public ResponseEntity<Void> deleteModel(@PathVariable UUID modelId) {
        aiModelConfigService.deleteModel(modelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/models/evaluations/latest")
    public ResponseEntity<List<AiModelEvaluationDTO>> listLatestEvaluations() {
        return ResponseEntity.ok(aiModelEvaluationService.listLatest());
    }

    @PostMapping("/models/{modelId}/evaluations")
    public ResponseEntity<AiModelEvaluationDTO> startEvaluation(
            @PathVariable UUID modelId,
            @Valid @RequestBody AiModelEvaluationStartRequestDTO request
    ) {
        return ResponseEntity.accepted().body(
                aiModelEvaluationService.start(modelId, request.mode(), request.judgeModelConfigId())
        );
    }

    @PostMapping("/models/{modelId}/evaluations/{runId}/cancel")
    public ResponseEntity<AiModelEvaluationDTO> cancelEvaluation(
            @PathVariable UUID modelId,
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(aiModelEvaluationService.cancel(modelId, runId));
    }
}
