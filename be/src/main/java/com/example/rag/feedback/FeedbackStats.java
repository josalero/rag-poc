package com.example.rag.feedback;

public record FeedbackStats(
        int total,
        int helpful,
        int notHelpful,
        double helpfulRate
) {
}
