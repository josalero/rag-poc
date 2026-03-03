package com.example.rag.eval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evals")
public class RagEvalController {

    private final RagEvalService ragEvalService;

    public RagEvalController(RagEvalService ragEvalService) {
        this.ragEvalService = ragEvalService;
    }

    @PostMapping("/run")
    public ResponseEntity<EvalRunResponse> run(@RequestBody(required = false) EvalRunRequest request) {
        return ResponseEntity.ok(ragEvalService.run(request));
    }
}
