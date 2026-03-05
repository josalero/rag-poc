package com.example.rag.feature.query.controller;

import com.example.rag.feature.query.model.QueryRequest;
import com.example.rag.feature.query.model.QueryResponse;
import com.example.rag.feature.query.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class SkillsQueryController {

    private final RagService ragService;

    public SkillsQueryController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = ragService.query(
                request.question().trim(),
                request.maxResults(),
                request.minScore(),
                request.page(),
                request.pageSize(),
                request.useFeedbackTuning(),
                request.scopeId());
        return ResponseEntity.ok(response);
    }
}
