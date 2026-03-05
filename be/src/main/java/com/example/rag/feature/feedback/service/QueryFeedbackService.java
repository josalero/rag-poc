package com.example.rag.feature.feedback.service;

import com.example.rag.feature.feedback.model.*;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class QueryFeedbackService {

    private static final int MAX_ENTRIES = 1_000;
    private static final double DEFAULT_BASELINE_SCORE = 0.75d;

    private final CopyOnWriteArrayList<QueryFeedbackEntry> entries = new CopyOnWriteArrayList<>();

    public QueryFeedbackEntry addFeedback(QueryFeedbackRequest request) {
        QueryFeedbackEntry entry = new QueryFeedbackEntry(
                request.question().trim(),
                request.answer().trim(),
                request.helpful(),
                request.notes() != null ? request.notes().trim() : "",
                request.minScoreUsed(),
                request.avgSourceScore(),
                Instant.now());
        entries.add(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        return entry;
    }

    public FeedbackStats stats() {
        int total = entries.size();
        int helpful = (int) entries.stream().filter(QueryFeedbackEntry::helpful).count();
        int notHelpful = total - helpful;
        double helpfulRate = total == 0 ? 0.0 : (helpful * 100.0) / total;
        Double helpfulAvg = averageSourceScore(true);
        Double notHelpfulAvg = averageSourceScore(false);
        double recommendedMinScore = recommendMinScore(DEFAULT_BASELINE_SCORE);
        return new FeedbackStats(total, helpful, notHelpful, helpfulRate, recommendedMinScore, helpfulAvg, notHelpfulAvg);
    }

    public List<QueryFeedbackEntry> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<QueryFeedbackEntry> snapshot = List.copyOf(entries);
        int size = snapshot.size();
        int from = Math.max(0, size - safeLimit);
        List<QueryFeedbackEntry> tail = snapshot.subList(from, size);
        return tail.reversed();
    }

    public double recommendMinScore(double baseline) {
        double safeBaseline = clampScore(baseline);
        if (entries.isEmpty()) {
            return safeBaseline;
        }

        Double helpfulAvg = averageSourceScore(true);
        Double notHelpfulAvg = averageSourceScore(false);
        if (helpfulAvg != null && notHelpfulAvg != null) {
            double midpoint = (helpfulAvg + notHelpfulAvg) / 2.0d;
            return clampScore(midpoint);
        }

        int total = entries.size();
        int helpful = (int) entries.stream().filter(QueryFeedbackEntry::helpful).count();
        double helpfulRate = total == 0 ? 0.0d : (helpful * 100.0d) / total;
        if (helpfulRate < 40.0d) {
            return clampScore(safeBaseline + 0.05d);
        }
        if (helpfulRate > 70.0d) {
            return clampScore(safeBaseline - 0.05d);
        }
        return safeBaseline;
    }

    private Double averageSourceScore(boolean helpful) {
        List<Double> scores = entries.stream()
                .filter(entry -> entry.helpful() == helpful)
                .map(QueryFeedbackEntry::avgSourceScore)
                .filter(score -> score != null && score >= 0.0d && score <= 1.0d)
                .toList();
        if (scores.isEmpty()) {
            return null;
        }
        double sum = scores.stream().mapToDouble(Double::doubleValue).sum();
        return sum / scores.size();
    }

    private static double clampScore(double score) {
        return Math.max(0.0d, Math.min(1.0d, score));
    }
}
