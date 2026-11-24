import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One result row. Column names are upper-cased; values are accessed leniently (never throws on null). */
final class Row {
    private final Map<String, Object> values = new LinkedHashMap<>();

    void put(String column, Object value) {
        values.put(column.toUpperCase(), value);
    }

    boolean has(String column) {
        return values.containsKey(column);
    }

    Set<String> columns() {
        return values.keySet();
    }

    Object raw(String column) {
        return values.get(column);
    }

    boolean isNull(String column) {
        return values.get(column) == null;
    }

    /** Trimmed text. Dates print as yyyy-MM-dd (plus time only when the time part is not midnight). */
    String str(String column) {
        Object o = values.get(column);
        if (o == null) {
            return "";
        }
        if (o instanceof Timestamp t) {
            String s = t.toLocalDateTime().toString().replace('T', ' ');
            return s.endsWith(" 00:00") ? s.substring(0, 10) : s;
        }
        if (o instanceof BigDecimal b) {
            return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
        }
        return o.toString().trim();
    }

    BigDecimal num(String column) {
        Object o = values.get(column);
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (o != null) {
            try {
                return new BigDecimal(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return BigDecimal.ZERO;
    }

    int integer(String column) {
        return num(column).intValue();
    }

    boolean yes(String column) {
        return "Y".equalsIgnoreCase(str(column));
    }
}
