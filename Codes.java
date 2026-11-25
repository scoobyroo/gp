import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes PeopleSoft translate values (PSXLATITEM) so that option codes are explained
 * using the labels defined in the data itself instead of hard-coded text.
 * Reads the PSXLATITEM.xml export through {@link XmlDb}.
 */
final class Codes {
    private final XmlDb db;
    private final String today;
    private final Map<String, String> cache = new HashMap<>();

    /** Uses the current date as the "latest effective on or before" limit. */
    Codes(XmlDb db) {
        this(db, LocalDate.now().toString());
    }

    /** Uses the given date (YYYY-MM-DD, a time part is ignored) as the "latest effective on or before" limit. */
    Codes(XmlDb db, String today) {
        this.db = db;
        this.today = Table.date(today);
    }

    /** Long name for a translate value, or the raw value when none is defined. */
    String label(String field, String value) {
        if (value == null || Text.isBlank(value)) {
            return "";
        }
        String key = field + "|" + value;
        String hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        String text = lookup(field, value);
        if (text == null) {
            text = value;
        }
        cache.put(key, text);
        return text;
    }

    /** "Label (CODE)" - handy when the code itself is meaningful to a payroll analyst. */
    String both(String field, String value) {
        String l = label(field, value);
        return l.equals(value) ? value : l + " (" + value + ")";
    }

    /**
     * XLATLONGNAME of the PSXLATITEM row with FIELDNAME and FIELDVALUE equal (case-sensitive), EFF_STATUS = 'A'
     * and the latest EFFDT on or before today; null when no such row exists (or the export is missing).
     */
    private String lookup(String field, String value) {
        String wantField = field.trim();
        String wantValue = value.trim();
        List<Row> all = db.rows("PSXLATITEM");
        Row best = null;
        String bestDate = null;
        for (Row r : all) {
            if (!r.str("FIELDNAME").equals(wantField) || !r.str("FIELDVALUE").equals(wantValue)
                    || !"A".equals(r.str("EFF_STATUS"))) {
                continue;
            }
            String eff = Table.date(r, "EFFDT");
            if (eff.compareTo(today) > 0) {
                continue;
            }
            if (best == null || eff.compareTo(bestDate) > 0) {
                best = r;
                bestDate = eff;
            }
        }
        return best == null ? null : best.str("XLATLONGNAME");
    }
}
