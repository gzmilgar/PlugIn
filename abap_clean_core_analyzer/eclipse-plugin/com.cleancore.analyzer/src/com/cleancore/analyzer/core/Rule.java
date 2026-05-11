package com.cleancore.analyzer.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Rule {
    private final String ruleId;
    private final String name;
    private final Severity severity;
    private final Category category;
    private final String description;
    private final String suggestion;
    private final Pattern[] patterns;
    private final Pattern[] excludePatterns;
    private final String cleanCoreApi;
    private final boolean resolveTableMapping;

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
        "(?:FROM|JOIN|INTO|UPDATE|MODIFY|INSERT|DELETE\\s+FROM)\\s+(?:@)?([A-Z][A-Z0-9_]+)",
        Pattern.CASE_INSENSITIVE
    );

    public Rule(String ruleId, String name, Severity severity, Category category,
                String description, String suggestion,
                String[] patternStrs, String[] excludePatternStrs, String cleanCoreApi) {
        this(ruleId, name, severity, category, description, suggestion,
             patternStrs, excludePatternStrs, cleanCoreApi, false);
    }

    public Rule(String ruleId, String name, Severity severity, Category category,
                String description, String suggestion,
                String[] patternStrs, String[] excludePatternStrs,
                String cleanCoreApi, boolean resolveTableMapping) {
        this.ruleId = ruleId;
        this.name = name;
        this.severity = severity;
        this.category = category;
        this.description = description;
        this.suggestion = suggestion;
        this.cleanCoreApi = cleanCoreApi != null ? cleanCoreApi : "";
        this.resolveTableMapping = resolveTableMapping;

        this.patterns = new Pattern[patternStrs.length];
        for (int i = 0; i < patternStrs.length; i++) {
            this.patterns[i] = Pattern.compile(patternStrs[i], Pattern.CASE_INSENSITIVE);
        }

        if (excludePatternStrs != null) {
            this.excludePatterns = new Pattern[excludePatternStrs.length];
            for (int i = 0; i < excludePatternStrs.length; i++) {
                this.excludePatterns[i] = Pattern.compile(excludePatternStrs[i], Pattern.CASE_INSENSITIVE);
            }
        } else {
            this.excludePatterns = new Pattern[0];
        }
    }

    public Finding check(String text, int startLine, int endLine) {
        String upper = text.toUpperCase();

        for (Pattern ep : excludePatterns) {
            if (ep.matcher(upper).find()) {
                return null;
            }
        }

        for (Pattern p : patterns) {
            Matcher m = p.matcher(upper);
            if (m.find()) {
                String apiSuggestion = cleanCoreApi;
                String suggestionText = suggestion;

                if (resolveTableMapping) {
                    String tableName = extractTableName(upper);
                    if (tableName != null) {
                        String cdsView = TableCdsMapping.lookup(tableName);
                        if (cdsView != null) {
                            apiSuggestion = cdsView;
                            suggestionText = "Replace " + tableName
                                + " with released CDS View: " + cdsView;
                        }
                    }
                }

                return new Finding(
                    ruleId, name, severity, category,
                    description + ": " + m.group(0).trim(),
                    suggestionText, startLine, endLine,
                    m.group(0).trim(), apiSuggestion
                );
            }
        }
        return null;
    }

    private String extractTableName(String upper) {
        Matcher m = TABLE_NAME_PATTERN.matcher(upper);
        while (m.find()) {
            String candidate = m.group(1).trim();
            if (!candidate.startsWith("Z") && !candidate.startsWith("Y")
                && !candidate.startsWith("LT_") && !candidate.startsWith("GT_")
                && !candidate.startsWith("LS_") && !candidate.startsWith("GS_")
                && !candidate.startsWith("ET_") && !candidate.startsWith("IT_")
                && !candidate.equals("TABLE") && !candidate.equals("DATA")
                && !candidate.equals("CORRESPONDING")) {
                return candidate;
            }
        }
        return null;
    }

    public Severity getSeverity() { return severity; }
    public String getRuleId() { return ruleId; }
}
