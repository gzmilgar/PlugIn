package com.cleancore.analyzer.core;

public class Finding {
    private final String ruleId;
    private final String ruleName;
    private final Severity severity;
    private final Category category;
    private final String message;
    private final String suggestion;
    private final int lineStart;
    private final int lineEnd;
    private final String matchedText;
    private final String cleanCoreApi;
    private double effortDays;

    public Finding(String ruleId, String ruleName, Severity severity, Category category,
                   String message, String suggestion, int lineStart, int lineEnd,
                   String matchedText, String cleanCoreApi) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.severity = severity;
        this.category = category;
        this.message = message;
        this.suggestion = suggestion;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.matchedText = matchedText;
        this.cleanCoreApi = cleanCoreApi;
        this.effortDays = 0;
    }

    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public Severity getSeverity() { return severity; }
    public Category getCategory() { return category; }
    public String getMessage() { return message; }
    public String getSuggestion() { return suggestion; }
    public int getLineStart() { return lineStart; }
    public int getLineEnd() { return lineEnd; }
    public String getMatchedText() { return matchedText; }
    public String getCleanCoreApi() { return cleanCoreApi; }
    public double getEffortDays() { return effortDays; }
    public void setEffortDays(double days) { this.effortDays = days; }
}
