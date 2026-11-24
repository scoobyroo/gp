import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cached access to PS_GP_PIN and PS_GP_PIN_TYPE. */
final class Pins {
    private final Db db;
    private final Map<Integer, Pin> byNum = new HashMap<>();
    private final Map<String, String> typeNames = new HashMap<>();
    private boolean typesLoaded;

    Pins(Db db) {
        this.db = db;
    }

    Pin get(int num) {
        if (num == 0) {
            return null;
        }
        Pin p = byNum.get(num);
        if (p == null && !byNum.containsKey(num)) {
            List<Row> r = db.tryQuery("SELECT * FROM PS_GP_PIN WHERE PIN_NUM = ?", num);
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
            for (Row r : db.tryQuery("SELECT PIN_TYPE, DESCR FROM PS_GP_PIN_TYPE")) {
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
    List<Pin> find(String text, int limit) throws java.sql.SQLException {
        String t = text.trim();
        List<Row> rows;
        if (t.matches("\\d+")) {
            rows = db.query("SELECT * FROM PS_GP_PIN WHERE PIN_NUM = ?", Integer.parseInt(t));
        } else {
            String u = t.toUpperCase();
            rows = db.query("SELECT * FROM PS_GP_PIN WHERE UPPER(PIN_NM) = ? ORDER BY PIN_CODE", u);
            if (rows.isEmpty()) {
                rows = db.query("SELECT * FROM PS_GP_PIN WHERE UPPER(PIN_CODE) = ? ORDER BY PIN_CODE", u);
            }
            if (rows.isEmpty()) {
                rows = db.query("SELECT * FROM PS_GP_PIN WHERE UPPER(PIN_CODE) LIKE ? ORDER BY PIN_CODE", u + "%");
            }
            if (rows.isEmpty()) {
                rows = db.query("SELECT * FROM PS_GP_PIN WHERE UPPER(PIN_NM) LIKE ? OR UPPER(DESCR) LIKE ? "
                        + "ORDER BY PIN_TYPE, PIN_CODE", "%" + u + "%", "%" + u + "%");
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
}
