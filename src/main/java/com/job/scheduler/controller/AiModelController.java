package com.job.scheduler.controller;

import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.service.AiModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/app/v1/ai")
public class AiModelController {
    private final AiModelConfigService aiModelConfigService;

    @GetMapping("/models")
    public ResponseEntity<List<AiModelConfigDTO>> listModels() {
        return ResponseEntity.ok(aiModelConfigService.listEnabledModels());
    }
}
