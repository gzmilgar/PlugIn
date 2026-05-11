package com.cleancore.analyzer.core;

import java.util.ArrayList;
import java.util.List;

public class ABAPParser {

    public List<ABAPStatement> parse(String source) {
        String[] lines = source.split("\\n");
        List<ABAPStatement> statements = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        List<String> raw = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();

            if (stripped.isEmpty() || stripped.startsWith("*")) {
                continue;
            }

            String clean = stripInlineComment(stripped);
            if (clean.isEmpty()) {
                continue;
            }

            if (buf.length() == 0) {
                start = i + 1;
            }

            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(clean);
            raw.add(lines[i]);

            if (clean.endsWith(".")) {
                String text = buf.toString();
                text = text.substring(0, text.length() - 1).trim();
                statements.add(new ABAPStatement(text, new ArrayList<>(raw), start, i + 1));
                buf.setLength(0);
                raw.clear();
            }
        }

        if (buf.length() > 0) {
            statements.add(new ABAPStatement(buf.toString().trim(), new ArrayList<>(raw), start, lines.length));
        }

        return statements;
    }

    private static String stripInlineComment(String line) {
        boolean inSq = false;
        boolean inBt = false;
        boolean inTpl = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\'' && !inBt && !inTpl) {
                inSq = !inSq;
            } else if (ch == '`' && !inSq && !inTpl) {
                inBt = !inBt;
            } else if (ch == '|' && !inSq && !inBt) {
                inTpl = !inTpl;
            } else if (ch == '"' && !inSq && !inBt && !inTpl) {
                return line.substring(0, i).trim();
            }
        }
        return line;
    }
}
