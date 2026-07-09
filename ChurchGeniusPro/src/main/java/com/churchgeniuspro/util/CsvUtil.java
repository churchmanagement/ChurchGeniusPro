package com.churchgeniuspro.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free CSV reader following RFC&nbsp;4180: quoted fields,
 * escaped quotes ({@code ""}), and commas/newlines embedded inside quotes.
 * Handles both {@code \n} and {@code \r\n} line endings. Used by the ETL extract
 * stage to turn an uploaded CSV into rows before they are stored verbatim as JSON.
 *
 * <p>Intentionally small and side-effect free so it can be unit-tested in
 * isolation. It does not infer types or trim values beyond what RFC 4180 requires.
 */
public final class CsvUtil {

    private CsvUtil() {}

    /** Parse the whole CSV text into a list of rows, each a list of string cells. */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;

        // Strip a UTF-8 BOM if present.
        if (text.charAt(0) == '﻿') text = text.substring(1);

        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();

        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {  // escaped quote
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }

            switch (c) {
                case '"':
                    inQuotes = true;
                    break;
                case ',':
                    row.add(cell.toString());
                    cell.setLength(0);
                    break;
                case '\r':
                    // swallow; the '\n' (or end) finishes the row
                    if (i + 1 < n && text.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    break;
                case '\n':
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    break;
                default:
                    cell.append(c);
            }
        }

        // Flush the final cell/row if the text didn't end with a newline.
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** True if a parsed row is entirely empty (e.g. a trailing blank line). */
    public static boolean isBlankRow(List<String> row) {
        if (row == null || row.isEmpty()) return true;
        for (String c : row) {
            if (c != null && !c.trim().isEmpty()) return false;
        }
        return true;
    }
}
