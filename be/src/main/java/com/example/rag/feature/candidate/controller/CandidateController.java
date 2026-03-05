package com.example.rag.feature.candidate.controller;

import com.example.rag.feature.candidate.model.*;
import com.example.rag.feature.candidate.service.CandidateProfileService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final CandidateProfileService candidateProfileService;

    public CandidateController(CandidateProfileService candidateProfileService) {
        this.candidateProfileService = candidateProfileService;
    }

    @GetMapping
    public ResponseEntity<CandidateSearchResponse> listCandidates(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ResponseEntity.ok(candidateProfileService.search(search, skill, location, sort, page, pageSize));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CandidateProfile> getCandidate(@PathVariable String id) {
        return candidateProfileService.getById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
