package com.app.maria.domain.risk.calendar;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small strict RFC-style reader: BOM, quoted commas, escaped quotes and newlines. */
final class CalendarCsv {
    private CalendarCsv() {}

    static List<Map<String, String>> read(byte[] bytes) throws CharacterCodingException {
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        if (text.startsWith("\ufeff")) text = text.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else value.append(c);
            } else if (c == '"') {
                if (closed || value.length() != 0) throw new IllegalArgumentException();
                quoted = true;
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(value.toString());
                value.setLength(0);
                closed = false;
                if (c != ',') {
                    if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
            } else {
                if (closed) throw new IllegalArgumentException();
                value.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException();
        if (closed || value.length() > 0 || !row.isEmpty()) {
            row.add(value.toString());
            rows.add(row);
        }
        if (rows.size() < 2 || rows.get(0).stream().distinct().count() != rows.get(0).size())
            throw new IllegalArgumentException();
        List<String> headers = rows.get(0);
        List<Map<String, String>> result = new ArrayList<>();
        for (List<String> values : rows.subList(1, rows.size())) {
            if (values.size() != headers.size()) throw new IllegalArgumentException();
            Map<String, String> mapped = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) mapped.put(headers.get(i), values.get(i));
            result.add(mapped);
        }
        return result;
    }
}
