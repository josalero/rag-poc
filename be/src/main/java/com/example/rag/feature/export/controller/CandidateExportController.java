package com.example.rag.feature.export.controller;

import com.example.rag.feature.candidate.model.CandidateProfile;
import com.example.rag.feature.candidate.service.CandidateProfileService;
import com.example.rag.feature.candidate.model.CandidateSearchResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/export")
public class CandidateExportController {

    private final CandidateProfileService candidateProfileService;

    public CandidateExportController(CandidateProfileService candidateProfileService) {
        this.candidateProfileService = candidateProfileService;
    }

    @GetMapping("/candidates.csv")
    public ResponseEntity<byte[]> exportCandidatesCsv(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) String location,
            @RequestParam(required = false, defaultValue = "name_asc") String sort) {
        CandidateSearchResponse response = candidateProfileService.search(search, skill, location, sort, 1, 1000);
        StringBuilder csv = new StringBuilder();
        csv.append("candidate_id,display_name,email,phone,location,years_experience,top_skills,suggested_roles,source_files,last_ingested_at\\n");
        for (CandidateProfile candidate : response.items()) {
            csv.append(row(
                    candidate.id(),
                    candidate.displayName(),
                    candidate.email(),
                    candidate.phone(),
                    candidate.location(),
                    candidate.estimatedYearsExperience() != null ? candidate.estimatedYearsExperience().toString() : "",
                    String.join(" | ", safeList(candidate.significantSkills())),
                    String.join(" | ", safeList(candidate.suggestedRoles())),
                    String.join(" | ", safeList(candidate.sourceFilenames())),
                    candidate.lastIngestedAt() != null ? candidate.lastIngestedAt().toString() : ""
            ));
        }
        byte[] payload = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"candidates-export.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(payload);
    }

    private static String row(String... values) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(escapeCsv(values[i]));
        }
        row.append('\n');
        return row.toString();
    }

    private static List<String> safeList(List<String> values) {
        return values != null ? values : List.of();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
