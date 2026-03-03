package com.example.rag.candidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CandidateNameExtractor {

    private static final Set<String> NAME_BLOCKLIST = Set.of(
            "summary", "experience", "education", "skills", "projects", "profile", "contact",
            "objective", "certifications", "resume", "curriculum", "vitae", "linkedin", "github",
            "personal", "information", "pdf"
    );

    private static final Set<String> NAME_ROLE_NOISE = Set.of(
            "engineer", "developer", "architect", "manager", "consultant", "intern", "software",
            "qa", "sdet", "tester", "devops", "platform", "cloud", "frontend", "backend",
            "full", "stack", "senior", "junior", "principal", "lead"
    );
    private static final Set<String> NAME_TECH_NOISE = Set.of(
            "api", "apis", "aws", "azure", "ci", "cd", "cicd", "docker", "kubernetes", "git", "gitlab",
            "github", "jira", "python", "ruby", "rails", "java", "javascript", "typescript", "csharp",
            "mysql", "postgresql", "mssql", "dynamodb", "redis", "elasticsearch", "databases", "scrum",
            "agile", "selenium", "cypress", "postman", "automation", "testing", "qa", "devops"
    );
    private static final Set<String> NAME_COMPANY_NOISE = Set.of(
            "inc", "inc.", "llc", "ltd", "ltda", "corp", "co", "company", "corporation", "s.a", "sa"
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
    private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("^[\\p{L}][\\p{L}\\p{M}.\\-']*$");
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
            LinkedHashSet<String> variants = new LinkedHashSet<>(expandCandidateLineVariants(candidateLines.get(i)));
            variants.addAll(expandAdjacentNameTokenVariants(candidateLines, i));
            for (String lineVariant : variants) {
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

    private static List<String> expandAdjacentNameTokenVariants(List<String> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size()) {
            return List.of();
        }
        String current = lines.get(index);
        if (!isSingleTokenUpperNameLine(current)) {
            return List.of();
        }

        List<String> variants = new ArrayList<>();
        if (index + 1 < lines.size() && isSingleTokenUpperNameLine(lines.get(index + 1))) {
            variants.add(current + " " + lines.get(index + 1));
            if (index + 2 < lines.size() && isSingleTokenUpperNameLine(lines.get(index + 2))) {
                variants.add(current + " " + lines.get(index + 1) + " " + lines.get(index + 2));
            }
        }
        return variants;
    }

    private static boolean isSingleTokenUpperNameLine(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = normalizePotentialNameText(value).replaceAll("\\s+", " ").trim();
        if (compact.isBlank() || compact.contains("@") || compact.matches(".*\\d.*")) {
            return false;
        }
        String[] tokens = compact.split("\\s+");
        if (tokens.length != 1) {
            return false;
        }
        String token = tokens[0].replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
        if (token.length() < 2 || token.length() > 30) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (NAME_BLOCKLIST.contains(lower)
                || NAME_ROLE_NOISE.contains(lower)
                || NAME_TECH_NOISE.contains(lower)
                || NAME_COMPANY_NOISE.contains(lower)
                || LOCATION_HINT_WORDS.contains(lower)) {
            return false;
        }
        return isUppercaseToken(token);
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
        // Handle duplicated downloader suffixes such as ".pdf-1" before the final extension.
        for (int i = 0; i < 3; i++) {
            String next = base.replaceAll("(?i)\\.pdf(?:[-_ ]?\\d+)?$", "");
            if (next.equals(base)) {
                break;
            }
            base = next;
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
        String compact = normalizePotentialNameText(line).replaceAll("\\s+", " ").trim();
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
        if (lineWords.stream().anyMatch(NAME_COMPANY_NOISE::contains)) {
            return "";
        }
        long techNoiseHits = lineWords.stream().filter(NAME_TECH_NOISE::contains).count();
        if (techNoiseHits >= 2) {
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
            token = token.replaceAll("\\.+$", "");
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

    private static String normalizePotentialNameText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace('\u0131', 'i')
                .replace('\u0130', 'I');
        return normalized.replaceAll("\\p{M}+", "");
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
        String cleaned = line.trim().replaceAll("^[^\\p{L}]+", "").trim();
        Matcher matcher = NAME_WITH_ROLE_SUFFIX_PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            return cleaned;
        }
        return stripTrailingRoleTokens(matcher.group(1).trim());
    }

    private static String stripTrailingRoleTokens(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(value.trim().split("\\s+")));
        while (tokens.size() > 1 && isRoleNoiseToken(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return String.join(" ", tokens).trim();
    }

    private static boolean isRoleNoiseToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (normalized.isBlank()) {
            return false;
        }
        if ("fullstack".equals(normalized)) {
            return true;
        }
        return NAME_ROLE_NOISE.contains(normalized);
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
