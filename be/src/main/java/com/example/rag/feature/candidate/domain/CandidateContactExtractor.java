package com.example.rag.feature.candidate.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CandidateContactExtractor {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?\\d[\\d\\s().\\-]{7,}\\d)");
    private static final Pattern URL_PATTERN = Pattern.compile("((?:https?://)?(?:www\\.)?(?:linkedin\\.com|github\\.com)/[^\\s)]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB_URL_PATTERN = Pattern.compile("((?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}(?:/[^\\s)]*)?)", Pattern.CASE_INSENSITIVE);

    private CandidateContactExtractor() {
    }

    public static String extractEmail(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    public static String extractPhone(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = PHONE_PATTERN.matcher(text);
        while (matcher.find()) {
            String normalized = normalizePhoneNumber(matcher.group(1));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    public static String extractLinkedinUrl(String text) {
        return extractUrlByHost(text, "linkedin.com");
    }

    public static String extractGithubUrl(String text) {
        return extractUrlByHost(text, "github.com");
    }

    public static String extractPortfolioUrl(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = WEB_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (start > 0 && text.charAt(start - 1) == '@') {
                continue;
            }
            if (end < text.length() && text.charAt(end) == '@') {
                continue;
            }
            String raw = matcher.group(1).trim();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("linkedin.com") || lower.contains("github.com")) {
                continue;
            }
            if (lower.startsWith("mailto:")) {
                continue;
            }
            return raw.startsWith("http://") || raw.startsWith("https://")
                    ? raw
                    : "https://" + raw.replaceFirst("^www\\.", "");
        }
        return "";
    }

    private static String normalizePhoneNumber(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return "";
        }
        String compact = rawPhone.trim().replaceAll("\\s+", " ");
        compact = compact.replaceAll("(?i)(?:ext\\.?|extension|x)\\s*\\d{1,5}\\s*$", "").trim();
        if (compact.isBlank()) {
            return "";
        }

        boolean hasPlusPrefix = compact.startsWith("+");
        String digits = compact.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15) {
            return "";
        }

        if (hasPlusPrefix) {
            return "+" + digits;
        }
        if (digits.length() == 10) {
            return "+1" + digits;
        }
        return "+" + digits;
    }

    private static String extractUrlByHost(String text, String host) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains(host)) {
                return raw.startsWith("http://") || raw.startsWith("https://")
                        ? raw
                        : "https://" + raw.replaceFirst("^www\\.", "");
            }
        }
        return "";
    }
}
