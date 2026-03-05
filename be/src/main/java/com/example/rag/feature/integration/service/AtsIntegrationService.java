package com.example.rag.feature.integration.service;

import com.example.rag.feature.integration.model.*;

import com.example.rag.feature.candidate.service.CandidateProfileService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class AtsIntegrationService {

    private static final int MAX_EVENTS = 200;

    private final ConcurrentLinkedDeque<AtsPushEvent> events = new ConcurrentLinkedDeque<>();
    private final CandidateProfileService candidateProfileService;

    public AtsIntegrationService(CandidateProfileService candidateProfileService) {
        this.candidateProfileService = candidateProfileService;
    }

    public AtsPushEvent push(AtsPushRequest request) {
        boolean candidateExists = candidateProfileService.getById(request.candidateId()).isPresent();
        AtsPushEvent event = new AtsPushEvent(
                request.candidateId(),
                request.jobId() != null ? request.jobId().trim() : "",
                request.notes() != null ? request.notes().trim() : "",
                candidateExists ? "accepted" : "rejected_unknown_candidate",
                Instant.now()
        );
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
        return event;
    }

    public List<AtsPushEvent> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_EVENTS));
        List<AtsPushEvent> out = new ArrayList<>(safeLimit);
        int count = 0;
        for (AtsPushEvent event : events) {
            out.add(event);
            count += 1;
            if (count >= safeLimit) {
                break;
            }
        }
        return out;
    }
}
