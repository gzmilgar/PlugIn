package com.cleancore.analyzer.core;

import java.util.List;

public class ABAPStatement {
    private final String text;
    private final List<String> rawLines;
    private final int startLine;
    private final int endLine;

    public ABAPStatement(String text, List<String> rawLines, int startLine, int endLine) {
        this.text = text;
        this.rawLines = rawLines;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public String getText() { return text; }
    public List<String> getRawLines() { return rawLines; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
}
