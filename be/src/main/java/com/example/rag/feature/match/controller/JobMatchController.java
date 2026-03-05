package com.example.rag.feature.match.controller;

import com.example.rag.feature.match.model.*;
import com.example.rag.feature.match.service.JobMatchService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/match")
public class JobMatchController {

    private final JobMatchService jobMatchService;

    public JobMatchController(JobMatchService jobMatchService) {
        this.jobMatchService = jobMatchService;
    }

    @PostMapping
    public ResponseEntity<JobMatchResponse> match(@Valid @RequestBody JobMatchRequest request) {
        return ResponseEntity.ok(jobMatchService.match(request));
    }
}
