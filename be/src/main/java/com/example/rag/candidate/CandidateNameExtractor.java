package com.example.rag.candidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CandidateNameExtractor {

    private static final Set<String> NAME_BLOCKLIST = Set.of(
            "summary", "experience", "education", "skills", "projects", "profile", "contact",
            "objective", "certifications", "resume", "curriculum", "vitae", "linkedin", "github"
    );

    private static final Set<String> NAME_ROLE_NOISE = Set.of(
            "engineer", "developer", "architect", "manager", "consultant", "intern", "software",
            "qa", "sdet", "tester", "devops", "platform", "cloud", "frontend", "backend",
            "full", "stack", "senior", "junior", "principal", "lead"
    );

    private static final Set<String> LOCATION_HINT_WORDS = Set.of(
            "remote", "hybrid", "onsite", "on", "site", "united", "states", "usa", "canada",
            "mexico", "costa", "rica", "spain", "germany", "france", "italy", "uk", "england",
            "brazil", "argentina", "colombia", "peru", "chile"
    );

    private static final Set<String> NAME_PARTICLES = Set.of(
            "de", "del", "la", "las", "los", "da", "dos", "do", "van", "von", "y"
    );

    private static final Set<String> US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DC", "DE", "FL", "GA", "HI", "IA", "ID",
            "IL", "IN", "KS", "KY", "LA", "MA", "MD", "ME", "MI", "MN", "MO", "MS", "MT", "NC",
            "ND", "NE", "NH", "NJ", "NM", "NV", "NY", "OH", "OK", "OR", "PA", "PR", "RI", "SC",
            "SD", "TN", "TX", "UT", "VA", "VT", "WA", "WI", "WV", "WY"
    );

    private static final Pattern CITY_STATE_PATTERN = Pattern.compile("([A-Z][a-z]+(?:[\\s\\-'][A-Z][a-z]+)*,\\s*([A-Z]{2}))");
    private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("^[\\p{L}][\\p{L}.\\-']*$");
    private static final Pattern NAME_SEGMENT_SPLIT_PATTERN = Pattern.compile("\\s(?:\\||•|·|—|–|:)\\s|\\s+-\\s");
    private static final Pattern NAME_WITH_ROLE_SUFFIX_PATTERN = Pattern.compile(
            "^([\\p{L}][\\p{L}.\\-']*(?:\\s+[\\p{L}][\\p{L}.\\-']*){1,3})\\s+"
                    + "(?:senior|junior|principal|lead|staff|software|qa|sdet|devops|backend|frontend|full[- ]?stack|"
                    + "engineering|engineer|developer|manager|architect)\\b.*$",
            Pattern.CASE_INSENSITIVE);

    private CandidateNameExtractor() {
    }

    static String extractDisplayName(String filename, String text) {
        List<String> candidateLines = Arrays.stream(text.split("\\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(20)
                .toList();

        String bestName = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidateLines.size(); i++) {
            for (String lineVariant : expandCandidateLineVariants(candidateLines.get(i))) {
                String candidate = normalizeCandidateName(lineVariant);
                if (candidate.isBlank()) {
                    continue;
                }
                double score = scoreNameCandidate(candidate, i);
                if (score > bestScore) {
                    bestScore = score;
                    bestName = candidate;
                }
            }
        }

        if (!bestName.isBlank()) {
            return bestName;
        }

        String fromEmail = nameFromEmail(text);
        if (!fromEmail.isBlank()) {
            return fromEmail;
        }

        return nameFromFilename(filename);
    }

    static boolean isPlausibleDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() < 3 || compact.length() > 80) {
            return false;
        }
        if (compact.contains("@") || compact.matches(".*\\d.*")) {
            return false;
        }
        String normalized = normalizeCandidateName(compact);
        return !normalized.isBlank() && normalized.equalsIgnoreCase(compact);
    }

    static boolean looksLikeSkillOrRolePhrase(String value, List<String> knownSkills) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("experience") || lower.contains("curriculum vitae")) {
            return true;
        }
        List<String> words = Arrays.stream(lower.split("[^a-z0-9+/#]+"))
                .filter(word -> !word.isBlank())
                .toList();
        if (words.size() < 2) {
            return false;
        }
        Set<String> knownSkillSet = knownSkills.stream()
                .map(skill -> skill.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        long skillOrRoleHits = words.stream()
                .filter(word -> knownSkillSet.contains(word) || NAME_ROLE_NOISE.contains(word))
                .count();
        return skillOrRoleHits >= Math.max(2, words.size() - 1);
    }

    static String nameFromFilename(String filename) {
        String base = filename == null ? "Candidate" : filename;
        int extensionIndex = base.lastIndexOf('.');
        if (extensionIndex > 0) {
            base = base.substring(0, extensionIndex);
        }
        String[] tokens = base.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].replaceAll("[^\\p{L}]", "");
            if (token.isBlank()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (NAME_BLOCKLIST.contains(lower)
                    || "email".equals(lower)
                    || "gmail".equals(lower)
                    || "yahoo".equals(lower)
                    || "hotmail".equals(lower)
                    || "outlook".equals(lower)
                    || "resume".equals(lower)
                    || "cv".equals(lower)
                    || "com".equals(lower)
                    || "net".equals(lower)
                    || "org".equals(lower)) {
                continue;
            }
            words.add(i > 0 && NAME_PARTICLES.contains(lower) ? lower : titleCaseToken(lower));
        }
        String result = String.join(" ", words).trim();
        return result.isEmpty() ? "Candidate" : result;
    }

    static String normalizeCandidateName(String line) {
        if (line == null) {
            return "";
        }
        String compact = line.replaceAll("\\s+", " ").trim();
        if (compact.isBlank() || compact.length() > 80) {
            return "";
        }
        compact = stripRoleSuffixFromNameLine(compact);
        if (compact.contains("@") || compact.matches(".*\\d.*") || compact.toLowerCase(Locale.ROOT).contains("http")) {
            return "";
        }
        if (looksLikeLocationPhrase(compact)) {
            return "";
        }

        List<String> lineWords = Arrays.stream(compact.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
                .filter(word -> !word.isBlank())
                .toList();
        if (lineWords.stream().anyMatch(NAME_BLOCKLIST::contains)) {
            return "";
        }
        if (lineWords.stream().anyMatch(NAME_ROLE_NOISE::contains)) {
            return "";
        }

        List<String> rawTokens = Arrays.stream(compact.replace(',', ' ').replace(';', ' ').split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();
        List<String> normalizedRawTokens = collapseSingleLetterRuns(rawTokens);
        String[] normalizedTokensArray = normalizedRawTokens.toArray(new String[0]);
        if (normalizedTokensArray.length < 2 || normalizedTokensArray.length > 6) {
            return "";
        }

        List<String> normalizedTokens = new ArrayList<>();
        for (int i = 0; i < normalizedTokensArray.length; i++) {
            String token = normalizedTokensArray[i].replaceAll("^[^\\p{L}]+|[^\\p{L}.\\-']+$", "");
            if (token.isBlank() || !NAME_TOKEN_PATTERN.matcher(token).matches()) {
                return "";
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (i > 0 && NAME_PARTICLES.contains(lower)) {
                normalizedTokens.add(lower);
            } else {
                normalizedTokens.add(titleCaseToken(lower));
            }
        }
        return String.join(" ", normalizedTokens).trim();
    }

    private static List<String> expandCandidateLineVariants(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String trimmed = line.trim();
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(trimmed);
        String repairedUppercaseFragments = repairBrokenUppercaseNameLine(trimmed);
        if (!repairedUppercaseFragments.isBlank()) {
            variants.add(repairedUppercaseFragments);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name:")) {
            variants.add(trimmed.substring(5).trim());
        } else if (lower.startsWith("name -")) {
            variants.add(trimmed.substring(6).trim());
        }

        for (String segment : NAME_SEGMENT_SPLIT_PATTERN.split(trimmed)) {
            String compact = segment.trim();
            if (!compact.isBlank()) {
                variants.add(compact);
            }
        }
        return variants.stream().toList();
    }

    private static String repairBrokenUppercaseNameLine(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        String compact = line.replaceAll("\\s+", " ").trim();
        if (compact.length() < 6 || compact.length() > 90 || compact.contains("@")) {
            return "";
        }

        List<String> rawTokens = Arrays.stream(compact.split("\\s+"))
                .map(token -> token.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", ""))
                .filter(token -> !token.isBlank())
                .toList();
        if (rawTokens.size() < 4 || rawTokens.size() > 10) {
            return "";
        }

        long shortTokens = rawTokens.stream().filter(token -> token.length() <= 2).count();
        long uppercaseTokens = rawTokens.stream().filter(CandidateNameExtractor::isUppercaseToken).count();
        if (shortTokens < 2 || uppercaseTokens < rawTokens.size() - 1) {
            return "";
        }

        List<String> firstPass = new ArrayList<>();
        for (int i = 0; i < rawTokens.size(); i++) {
            String token = rawTokens.get(i);
            if (token.length() == 1 && i + 1 < rawTokens.size()) {
                firstPass.add(token + rawTokens.get(++i));
            } else {
                firstPass.add(token);
            }
        }

        List<String> merged = new ArrayList<>();
        for (int i = 0; i < firstPass.size(); i++) {
            String current = firstPass.get(i);
            while (i + 1 < firstPass.size() && shouldMergeBrokenNameToken(current, firstPass.get(i + 1))) {
                current = current + firstPass.get(++i);
            }
            merged.add(current);
        }
        if (merged.size() < 2 || merged.size() > 5) {
            return "";
        }

        String candidate = String.join(" ", merged);
        String normalized = normalizeCandidateName(candidate);
        return normalized.isBlank() ? "" : normalized;
    }

    private static boolean shouldMergeBrokenNameToken(String current, String next) {
        if (current == null || next == null || current.isBlank() || next.isBlank()) {
            return false;
        }
        if (current.length() > 4 || next.length() > 6) {
            return false;
        }
        if (!startsWithVowel(next)) {
            return false;
        }
        return vowelCount(current) <= 1 && endsWithConsonant(current);
    }

    private static boolean startsWithVowel(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return isVowel(token.charAt(0));
    }

    private static boolean endsWithConsonant(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        char last = token.charAt(token.length() - 1);
        return Character.isLetter(last) && !isVowel(last);
    }

    private static int vowelCount(String token) {
        int count = 0;
        for (int i = 0; i < token.length(); i++) {
            if (isVowel(token.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isVowel(char ch) {
        return "AEIOUÁÉÍÓÚÜaeiouáéíóúü".indexOf(ch) >= 0;
    }

    private static boolean isUppercaseToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (!Character.isLetter(ch)) {
                continue;
            }
            hasLetter = true;
            if (!Character.isUpperCase(ch)) {
                return false;
            }
        }
        return hasLetter;
    }

    private static String stripRoleSuffixFromNameLine(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        Matcher matcher = NAME_WITH_ROLE_SUFFIX_PATTERN.matcher(line.trim());
        if (!matcher.matches()) {
            return line.trim();
        }
        return matcher.group(1).trim();
    }

    private static List<String> collapseSingleLetterRuns(List<String> tokens) {
        List<String> collapsed = new ArrayList<>();
        StringBuilder letterRun = new StringBuilder();
        for (String rawToken : tokens) {
            String token = rawToken.replaceAll("^[^\\p{L}]+|[^\\p{L}.\\-']+$", "");
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                letterRun.append(token);
                continue;
            }
            flushLetterRun(collapsed, letterRun);
            if (!token.isBlank()) {
                collapsed.add(token);
            }
        }
        flushLetterRun(collapsed, letterRun);
        return collapsed;
    }

    private static void flushLetterRun(List<String> target, StringBuilder letterRun) {
        if (letterRun.length() >= 2) {
            target.add(letterRun.toString());
        } else if (letterRun.length() == 1) {
            target.add(letterRun.toString() + ".");
        }
        letterRun.setLength(0);
    }

    private static double scoreNameCandidate(String name, int lineIndex) {
        String[] tokens = name.split("\\s+");
        int tokenCount = tokens.length;
        double score = 100.0 - (lineIndex * 4.0);
        if (tokenCount == 2 || tokenCount == 3) {
            score += 8.0;
        } else if (tokenCount == 4) {
            score += 4.0;
        }
        long particleCount = Arrays.stream(tokens)
                .map(token -> token.toLowerCase(Locale.ROOT))
                .filter(NAME_PARTICLES::contains)
                .count();
        score += Math.min(2.0, particleCount);
        if (looksLikeLocationPhrase(name)) {
            score -= 20.0;
        }
        return score;
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch == '-' || ch == '\'' || ch == '.') {
                sb.append(ch);
                capitalizeNext = true;
                continue;
            }
            if (capitalizeNext && Character.isLetter(ch)) {
                sb.append(Character.toUpperCase(ch));
                capitalizeNext = false;
            } else {
                sb.append(ch);
                capitalizeNext = false;
            }
        }
        return sb.toString();
    }

    private static String nameFromEmail(String text) {
        String email = CandidateContactExtractor.extractEmail(text);
        if (email.isBlank()) {
            return "";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "";
        }
        String local = email.substring(0, atIndex).toLowerCase(Locale.ROOT);
        String[] rawTokens = local.replaceAll("[^\\p{L}]+", " ").trim().split("\\s+");
        List<String> tokens = Arrays.stream(rawTokens)
                .filter(token -> !token.isBlank())
                .map(token -> token.replaceAll("\\d+", ""))
                .filter(token -> !token.isBlank())
                .toList();
        if (tokens.size() < 2 || tokens.size() > 5) {
            return "";
        }
        List<String> nameTokens = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!NAME_TOKEN_PATTERN.matcher(token).matches()) {
                return "";
            }
            String lower = token.toLowerCase(Locale.ROOT);
            nameTokens.add(i > 0 && NAME_PARTICLES.contains(lower) ? lower : titleCaseToken(lower));
        }
        return String.join(" ", nameTokens).trim();
    }

    private static boolean looksLikeLocationPhrase(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.trim();
        String lower = compact.toLowerCase(Locale.ROOT);
        if (lower.contains("remote") || lower.contains("hybrid") || lower.contains("onsite") || lower.contains("on-site")) {
            return true;
        }
        Matcher cityStateMatcher = CITY_STATE_PATTERN.matcher(compact);
        if (cityStateMatcher.find()) {
            String stateCode = cityStateMatcher.group(2).trim().toUpperCase(Locale.ROOT);
            if (US_STATE_CODES.contains(stateCode)) {
                return true;
            }
        }
        List<String> words = Arrays.stream(lower.split("[^\\p{L}]+"))
                .filter(word -> !word.isBlank())
                .toList();
        if (words.size() < 2 || words.size() > 5) {
            return false;
        }
        long hints = words.stream().filter(LOCATION_HINT_WORDS::contains).count();
        return hints >= 2;
    }
}
