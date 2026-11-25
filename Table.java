import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL-free query primitives over in-memory rows (replaces the WHERE / correlated MAX(EFFDT) / ROWNUM
 * constructs the database-backed tool used). Every helper returns a new list and never modifies its input.
 * Column names are matched upper-case, like {@link Row#put}. Dates are compared as YYYY-MM-DD text.
 */
final class Table {
    private Table() {
    }

    /** WHERE col = value (numeric comparison; rows with an empty or non-numeric column never match). */
    static List<Row> eq(List<Row> rows, String col, int value) {
        String c = col.toUpperCase();
        BigDecimal want = BigDecimal.valueOf(value);
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (numeric(r, c) != null && numeric(r, c).compareTo(want) == 0) {
                out.add(r);
            }
        }
        return out;
    }

    /** WHERE UPPER(col) = UPPER(value) on the trimmed column text (empty columns never match). */
    static List<Row> eq(List<Row> rows, String col, String value) {
        String c = col.toUpperCase();
        List<Row> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        String want = value.trim();
        for (Row r : rows) {
            if (!r.isNull(c) && r.str(c).equalsIgnoreCase(want)) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Effective-dated selection for one key: of the rows whose keyCol equals key and whose EFFDT is on or before
     * asof, keep those whose EFFDT is the maximum such EFFDT (several rows may share it, e.g. detail lines).
     * Returns no rows when every EFFDT is after asof. Input order is preserved.
     */
    static List<Row> effectiveLatest(List<Row> rows, String keyCol, int key, String asof) {
        return effectiveLatest(eq(rows, keyCol, key), keyCol, asof);
    }

    /** Same selection, applied independently to every distinct keyCol value in rows. Input order is preserved. */
    static List<Row> effectiveLatest(List<Row> rows, String keyCol, String asof) {
        String k = keyCol.toUpperCase();
        String limit = date(asof);
        Map<String, String> best = new LinkedHashMap<>();
        for (Row r : rows) {
            String eff = date(r, "EFFDT");
            if (eff.compareTo(limit) <= 0) {
                String key = r.str(k);
                String cur = best.get(key);
                if (cur == null || eff.compareTo(cur) > 0) {
                    best.put(key, eff);
                }
            }
        }
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            String want = best.get(r.str(k));
            if (want != null && date(r, "EFFDT").equals(want)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Keeps rows with an empty or on/before beginCol and an empty or on/after endCol (inclusive on both ends). */
    static List<Row> activeOn(List<Row> rows, String beginCol, String endCol, String asof) {
        String limit = date(asof);
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            String b = date(r, beginCol);
            String e = date(r, endCol);
            if ((b.isEmpty() || b.compareTo(limit) <= 0) && (e.isEmpty() || e.compareTo(limit) >= 0)) {
                out.add(r);
            }
        }
        return out;
    }

    /** WHERE UPPER(col) LIKE '%needle%'. The needle is expected to be upper-case already. */
    static List<Row> contains(List<Row> rows, String col, String upperNeedle) {
        String c = col.toUpperCase();
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.str(c).toUpperCase().contains(upperNeedle)) {
                out.add(r);
            }
        }
        return out;
    }

    /** WHERE UPPER(col) LIKE 'prefix%'. The prefix is expected to be upper-case already. */
    static List<Row> startsWith(List<Row> rows, String col, String upperPrefix) {
        String c = col.toUpperCase();
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.str(c).toUpperCase().startsWith(upperPrefix)) {
                out.add(r);
            }
        }
        return out;
    }

    /** WHERE col1 = pin OR col2 = pin OR ... */
    static List<Row> any(List<Row> rows, int pin, String... cols) {
        List<Row> out = new ArrayList<>();
        BigDecimal want = BigDecimal.valueOf(pin);
        for (Row r : rows) {
            for (String col : cols) {
                BigDecimal v = numeric(r, col.toUpperCase());
                if (v != null && v.compareTo(want) == 0) {
                    out.add(r);
                    break;
                }
            }
        }
        return out;
    }

    /** ORDER BY (stable sort on a copy). */
    static List<Row> sortBy(List<Row> rows, Comparator<Row> cmp) {
        List<Row> out = new ArrayList<>(rows);
        Collections.sort(out, cmp);
        return out;
    }

    /** ROWNUM &lt;= n (the first n rows; all rows when fewer, none when n is not positive). */
    static List<Row> first(List<Row> rows, int n) {
        if (n <= 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(rows.subList(0, Math.min(n, rows.size())));
    }

    /** The column as YYYY-MM-DD text: trimmed, with any time part (after 'T' or a blank, or beyond 10 chars) removed. */
    static String date(Row r, String col) {
        return r.isNull(col.toUpperCase()) ? "" : date(r.str(col.toUpperCase()));
    }

    /** A date text normalised to YYYY-MM-DD (see {@link #date(Row, String)}); null becomes empty. */
    static String date(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        int cut = t.indexOf('T');
        if (cut < 0) {
            cut = t.indexOf(' ');
        }
        if (cut >= 0) {
            t = t.substring(0, cut);
        }
        return t.length() > 10 ? t.substring(0, 10) : t;
    }

    /** The column as a number, or null when empty or not numeric. */
    private static BigDecimal numeric(Row r, String col) {
        if (r.isNull(col)) {
            return null;
        }
        try {
            return new BigDecimal(r.str(col));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
