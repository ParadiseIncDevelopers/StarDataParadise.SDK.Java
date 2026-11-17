package com.stardataparadise.sdk;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record SqlText(String wire, List<Integer> positions, Kind kind, String verb) {
    enum Kind { READ, ROUTINE, EXTENSION, WRITE }

    static SqlText parse(String input, boolean callable) throws SQLException {
        if (input == null || input.isBlank() || input.length() > Wire.MAX_FRAME)
            throw Wire.invalid("SQL is empty or too large");
        String text = input.strip();
        if (text.startsWith("{")) {
            if (!callable || !text.endsWith("}")) throw ValueCodec.unsupported("JDBC escape");
            text = text.substring(1, text.length() - 1).strip();
        }
        var first = new ArrayList<String>();
        var output = new StringBuilder(text.length());
        var positions = new ArrayList<Integer>();
        int i = 0;
        while (i < text.length()) {
            char current = text.charAt(i);
            if (current == '\'' || current == '"') {
                char delimiter = current;
                int begin = i++;
                boolean closed = false;
                while (i < text.length()) {
                    if (text.charAt(i++) != delimiter) continue;
                    if (i < text.length() && text.charAt(i) == delimiter) i++;
                    else { closed = true; break; }
                }
                if (!closed) throw Wire.invalid("Unterminated SQL quoted value");
                output.append(text, begin, i);
            } else if (text.startsWith("--", i)) {
                int begin = i;
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
                output.append(text, begin, i);
            } else if (text.startsWith("/*", i)) {
                int close = text.indexOf("*/", i + 2);
                if (close < 0) throw Wire.invalid("Unterminated SQL block comment");
                output.append(text, i, close + 2);
                i = close + 2;
            } else if (current == '?') {
                if (positions.size() == 128) throw Wire.invalid("Too many JDBC parameters");
                positions.add(output.length());
                output.append(':').append(parameterName(positions.size()));
                i++;
            } else if (current == ':' && i + 1 < text.length() && startsName(text.charAt(i + 1))) {
                int begin = i++;
                while (i < text.length() && continuesName(text.charAt(i))) i++;
                if (text.substring(begin + 1, i).toLowerCase(Locale.ROOT).startsWith("jdbc_p_"))
                    throw Wire.invalid("SQL contains a reserved JDBC parameter name");
                output.append(text, begin, i);
            } else if (startsName(current)) {
                int begin = i++;
                while (i < text.length() && continuesName(text.charAt(i))) i++;
                if (first.size() < 3) first.add(text.substring(begin, i).toUpperCase(Locale.ROOT));
                output.append(text, begin, i);
            } else {
                output.append(current);
                i++;
            }
        }
        if (first.isEmpty()) throw Wire.invalid("SQL has no statement");
        String verb = first.get(0);
        String next = first.size() > 1 ? first.get(1) : "";
        if (callable && !verb.equals("CALL")) throw ValueCodec.unsupported("callable syntax");
        Kind kind = switch (verb) {
            case "SELECT", "WITH", "EXPLAIN" -> Kind.READ;
            case "CALL", "DECLARE", "BEGIN" -> Kind.ROUTINE;
            case "HISTORY", "DECISION", "TRAIN", "EVALUATE", "PREDICT" -> Kind.EXTENSION;
            case "CREATE", "ALTER" -> next.equals("MODEL") ? Kind.EXTENSION : Kind.WRITE;
            default -> Kind.WRITE;
        };
        return new SqlText(output.toString(), List.copyOf(positions), kind, verb);
    }

    boolean isBasicDml() {
        return switch (verb) {
            case "INSERT", "UPDATE", "DELETE" -> true;
            default -> false;
        };
    }

    int parameters() { return positions.size(); }

    String wireWithOutPlaceholders(Set<Integer> outOnly) {
        if (outOnly.isEmpty()) return wire;
        var rendered = new StringBuilder(wire);
        for (int index = positions.size(); index > 0; index--) {
            if (outOnly.contains(index)) {
                int start = positions.get(index - 1);
                rendered.replace(start, start + 1 + parameterName(index).length(), "NULL");
            }
        }
        return rendered.toString();
    }

    static String parameterName(int position) { return "jdbc_p_" + position; }
    private static boolean startsName(char c) { return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static boolean continuesName(char c) { return startsName(c) || c >= '0' && c <= '9'; }
}
