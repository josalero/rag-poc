package com.example.rag.feedback;

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
@RequestMapping("/api/query/feedback")
public class QueryFeedbackController {

    private final QueryFeedbackService queryFeedbackService;

    public QueryFeedbackController(QueryFeedbackService queryFeedbackService) {
        this.queryFeedbackService = queryFeedbackService;
    }

    @PostMapping
    public ResponseEntity<QueryFeedbackEntry> submitFeedback(@Valid @RequestBody QueryFeedbackRequest request) {
        return ResponseEntity.ok(queryFeedbackService.addFeedback(request));
    }

    @GetMapping("/stats")
    public ResponseEntity<FeedbackStats> getStats() {
        return ResponseEntity.ok(queryFeedbackService.stats());
    }

    @GetMapping
    public ResponseEntity<List<QueryFeedbackEntry>> getRecent(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(queryFeedbackService.recent(limit));
    }
}
