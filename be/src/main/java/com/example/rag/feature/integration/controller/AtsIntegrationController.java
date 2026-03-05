package com.example.rag.feature.integration.controller;

import com.example.rag.feature.integration.model.*;
import com.example.rag.feature.integration.service.AtsIntegrationService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/integrations/ats")
public class AtsIntegrationController {

    private final AtsIntegrationService atsIntegrationService;

    public AtsIntegrationController(AtsIntegrationService atsIntegrationService) {
        this.atsIntegrationService = atsIntegrationService;
    }

    @PostMapping("/push")
    public ResponseEntity<AtsPushEvent> push(@Valid @RequestBody AtsPushRequest request) {
        return ResponseEntity.ok(atsIntegrationService.push(request));
    }

    @GetMapping("/events")
    public ResponseEntity<List<AtsPushEvent>> events(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(atsIntegrationService.recent(limit));
    }
}
