package com.snowworld.review;

import java.util.ArrayList;
import java.util.List;

/** RFC 4180 风格 CSV 解析: 支持双引号转义、字段内逗号与换行, 记录每条记录的起始行号。 */
public final class CsvParser {

    public record Row(int line, List<String> fields) {
    }

    private CsvParser() {
    }

    public static List<Row> parse(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<Row> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cur.append(c);
                }
            } else if (c == '"' && cur.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\r') {
                // 忽略, 由 \n 结束记录
            } else if (c == '\n') {
                fields.add(cur.toString());
                cur.setLength(0);
                addRow(rows, recordLine, fields);
                fields = new ArrayList<>();
                line++;
                recordLine = line;
            } else {
                cur.append(c);
            }
            i++;
        }
        if (!cur.isEmpty() || !fields.isEmpty()) {
            fields.add(cur.toString());
            addRow(rows, recordLine, fields);
        }
        return rows;
    }

    private static void addRow(List<Row> rows, int line, List<String> fields) {
        boolean blank = fields.stream().allMatch(f -> f.isBlank());
        if (!blank) {
            rows.add(new Row(line, List.copyOf(fields)));
        }
    }
}
