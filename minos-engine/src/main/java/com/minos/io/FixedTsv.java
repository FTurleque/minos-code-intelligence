package com.minos.io;

import java.io.IOException;

/** Allocation-bounded splitter for fixed-column TSV protocols. */
public final class FixedTsv {
    private FixedTsv() { }

    public static String[] splitExact(String line, int expectedFields, int lineNumber) throws IOException {
        if (line == null) throw new IllegalArgumentException("line must not be null");
        if (expectedFields < 1) throw new IllegalArgumentException("expectedFields must be positive");
        int[] separators = new int[Math.max(0, expectedFields - 1)];
        int found = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) != '\t') continue;
            if (found >= separators.length) {
                throw failure(lineNumber, "expected " + expectedFields + " tab-separated fields");
            }
            separators[found++] = index;
        }
        if (found != separators.length) {
            throw failure(lineNumber, "expected " + expectedFields + " tab-separated fields");
        }
        String[] values = new String[expectedFields];
        int start = 0;
        for (int field = 0; field < separators.length; field++) {
            int end = separators[field];
            values[field] = line.substring(start, end);
            start = end + 1;
        }
        values[expectedFields - 1] = line.substring(start);
        return values;
    }

    public static String firstField(String line, int lineNumber) throws IOException {
        if (line == null) throw new IllegalArgumentException("line must not be null");
        int separator = line.indexOf('\t');
        if (separator < 0) throw failure(lineNumber, "expected tab-separated fields");
        return line.substring(0, separator);
    }

    private static IOException failure(int lineNumber, String message) {
        return new IOException((lineNumber > 0 ? "line " + lineNumber + ": " : "") + message);
    }
}
