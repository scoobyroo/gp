import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes PeopleSoft translate values (PSXLATITEM) so that option codes are explained
 * using the labels defined in the database itself instead of hard-coded text.
 */
final class Codes {
    private final Db db;
    private final Map<String, String> cache = new HashMap<>();

    Codes(Db db) {
        this.db = db;
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
        List<Row> r = db.tryQuery(
                "SELECT XLATLONGNAME FROM PSXLATITEM x WHERE FIELDNAME = ? AND FIELDVALUE = ? AND EFF_STATUS = 'A' "
                        + "AND EFFDT = (SELECT MAX(y.EFFDT) FROM PSXLATITEM y WHERE y.FIELDNAME = x.FIELDNAME "
                        + "AND y.FIELDVALUE = x.FIELDVALUE AND y.EFF_STATUS = 'A' AND y.EFFDT <= SYSDATE)",
                field, value);
        String text = r.isEmpty() ? value : r.get(0).str("XLATLONGNAME");
        cache.put(key, text);
        return text;
    }

    /** "Label (CODE)" - handy when the code itself is meaningful to a payroll analyst. */
    String both(String field, String value) {
        String l = label(field, value);
        return l.equals(value) ? value : l + " (" + value + ")";
    }
}
