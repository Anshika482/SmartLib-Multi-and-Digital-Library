package com.smartlib.report;

import java.util.List;

/** RFC 4180 CSV with formula-injection protection (cells starting with = + - @ are prefixed). */
public final class CsvWriter {
    private CsvWriter() {
    }

    public static String write(List<String> header, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        line(sb, header.stream().map(h -> (Object) h).toList());
        for (List<Object> row : rows) {
            line(sb, row);
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(value instanceof Number)) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
