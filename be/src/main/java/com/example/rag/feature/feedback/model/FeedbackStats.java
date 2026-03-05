package com.example.rag.feature.feedback.model;

public record FeedbackStats(
        int total,
        int helpful,
        int notHelpful,
        double helpfulRate,
        double recommendedMinScore,
        Double helpfulAvgSourceScore,
        Double notHelpfulAvgSourceScore
) {
}
