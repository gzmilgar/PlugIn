package com.cleancore.analyzer.core;

public enum Category {
    DB_ACCESS("Database Access"),
    API_USAGE("API Usage"),
    UI("User Interface"),
    OBSOLETE("Obsolete Syntax"),
    ARCHITECTURE("Architecture");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}
