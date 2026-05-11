package com.cleancore.analyzer.core;

public enum Severity {
    CRITICAL("critical", 0),
    WARNING("warning", 1),
    INFO("info", 2);

    private final String label;
    private final int level;

    Severity(String label, int level) {
        this.label = label;
        this.level = level;
    }

    public String getLabel() { return label; }
    public int getLevel() { return level; }

    public static Severity fromString(String s) {
        for (Severity v : values()) {
            if (v.label.equalsIgnoreCase(s)) return v;
        }
        return INFO;
    }
}
