import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cached access to PS_GP_PIN and PS_GP_PIN_TYPE (read from the XML exports). */
final class Pins {
    private final XmlDb db;
    private final Map<Integer, Pin> byNum = new HashMap<>();
    private final Map<String, String> typeNames = new HashMap<>();
    private boolean typesLoaded;

    Pins(XmlDb db) {
        this.db = db;
    }

    Pin get(int num) {
        if (num == 0) {
            return null;
        }
        Pin p = byNum.get(num);
        if (p == null && !byNum.containsKey(num)) {
            List<Row> r = Table.eq(db.rows("PS_GP_PIN"), "PIN_NUM", num);
            p = r.isEmpty() ? null : new Pin(r.get(0));
            byNum.put(num, p);
        }
        return p;
    }

    /** Display name for a pin number, tolerant of dangling references. */
    String name(int num) {
        Pin p = get(num);
        return p == null ? "#" + num : p.name;
    }

    String typeOf(int num) {
        Pin p = get(num);
        return p == null ? "?" : p.type;
    }

    String typeName(String type) {
        if (!typesLoaded) {
            for (Row r : db.rows("PS_GP_PIN_TYPE")) {
                typeNames.put(r.str("PIN_TYPE"), r.str("DESCR"));
            }
            typesLoaded = true;
        }
        return typeNames.getOrDefault(type, type);
    }

    /**
     * Find elements from free text. A number is treated as PIN_NUM; otherwise the lookup tries, in order:
     * exact name, exact code, code prefix, then name/description substring. The first stage with hits wins.
     */
    List<Pin> find(String text, int limit) {
        String t = text.trim();
        List<Row> all = db.rows("PS_GP_PIN");
        List<Row> rows;
        if (t.matches("\\d+")) {
            rows = Table.eq(all, "PIN_NUM", Integer.parseInt(t));
        } else {
            String u = t.toUpperCase();
            rows = Table.sortBy(Table.eq(all, "PIN_NM", u), byColumns("PIN_CODE"));
            if (rows.isEmpty()) {
                rows = Table.sortBy(Table.eq(all, "PIN_CODE", u), byColumns("PIN_CODE"));
            }
            if (rows.isEmpty()) {
                // LIKE never matches a NULL column, so rows without a code are excluded
                rows = Table.sortBy(Table.startsWith(nonNull(all, "PIN_CODE"), "PIN_CODE", u),
                        byColumns("PIN_CODE"));
            }
            if (rows.isEmpty()) {
                Set<Row> hits = new HashSet<>(Table.contains(nonNull(all, "PIN_NM"), "PIN_NM", u));
                hits.addAll(Table.contains(nonNull(all, "DESCR"), "DESCR", u));
                List<Row> matched = new ArrayList<>();
                for (Row r : all) {
                    if (hits.contains(r)) {
                        matched.add(r);
                    }
                }
                rows = Table.sortBy(matched, byColumns("PIN_TYPE", "PIN_CODE"));
            }
        }
        List<Pin> out = new ArrayList<>();
        for (Row r : rows) {
            Pin p = new Pin(r);
            byNum.put(p.num, p);
            out.add(p);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static List<Row> nonNull(List<Row> rows, String col) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (!r.isNull(col)) {
                out.add(r);
            }
        }
        return out;
    }

    /** ORDER BY c1, c2 ... ascending, binary text order, NULLs last (as in Oracle). The sort is stable. */
    private static Comparator<Row> byColumns(final String... cols) {
        return new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                for (String c : cols) {
                    boolean na = a.isNull(c);
                    boolean nb = b.isNull(c);
                    if (na || nb) {
                        if (na && nb) {
                            continue;
                        }
                        return na ? 1 : -1;
                    }
                    int d = a.str(c).compareTo(b.str(c));
                    if (d != 0) {
                        return d;
                    }
                }
                return 0;
            }
        };
    }
}
