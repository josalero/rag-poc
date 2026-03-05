package com.example.rag.feature.audit.controller;

import com.example.rag.feature.audit.model.*;
import com.example.rag.feature.audit.service.IngestAuditService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ingest/audit")
public class IngestAuditController {

    private final IngestAuditService ingestAuditService;

    public IngestAuditController(IngestAuditService ingestAuditService) {
        this.ingestAuditService = ingestAuditService;
    }

    @GetMapping
    public ResponseEntity<List<IngestAuditRun>> recentRuns(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ingestAuditService.recentRuns(limit));
    }
}
