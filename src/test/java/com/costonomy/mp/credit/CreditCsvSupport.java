package com.costonomy.mp.credit;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;

/** Reads a CSV export back the way a spreadsheet would: quoted cells, doubled quotes, newlines inside quotes. */
final class CreditCsvSupport {

    private CreditCsvSupport() {
    }

    record Csv(int status, String contentType, String disposition, byte[] bytes, String text) {
        List<List<String>> rows() {
            return parse(text);
        }

        /** The row whose first cell is {@code label}, e.g. "Opening owed". */
        List<String> row(String label) {
            return rows().stream().filter(r -> !r.isEmpty() && r.get(0).equals(label)).findFirst().orElseThrow();
        }

        /** The rows after the header whose first cell is {@code firstHeader}. */
        List<List<String>> table(String firstHeader) {
            var all = rows();
            int at = -1;
            for (int i = 0; i < all.size(); i++) {
                if (!all.get(i).isEmpty() && all.get(i).get(0).equals(firstHeader)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                throw new AssertionError("no header row starting with " + firstHeader + " in:\n" + text);
            }
            return all.subList(at, all.size());
        }
    }

    static Csv get(MockMvc mvc, String token, String path) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn();
        var response = result.getResponse();
        byte[] bytes = response.getContentAsByteArray();
        return new Csv(response.getStatus(), response.getContentType(), response.getHeader("Content-Disposition"),
                bytes, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    static List<List<String>> parse(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\r') {
                // ends with the \n that follows
            } else if (c == '\n') {
                if (any || cell.length() > 0 || !row.isEmpty()) {
                    row.add(cell.toString());
                    rows.add(row);
                } else {
                    rows.add(new ArrayList<>());   // a blank line
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
