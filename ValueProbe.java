import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up what a specific payee actually got in a calendar run, so the generic description can be
 * shown next to real numbers. Reads the delivered result tables only (PS_GP_RSLT_PIN, _ACUM, _ERN_DED).
 */
final class ValueProbe {
    private static final String[][] ERN_DED_COLUMNS = {
        {"CALC_RSLT_VAL", "calc"}, {"CALC_ADJ_VAL", "adj"}, {"BASE_RSLT_VAL", "base"}, {"RATE_RSLT_VAL", "rate"},
        {"UNIT_RSLT_VAL", "unit"}, {"PCT_RSLT_VAL", "pct"}, {"RSLT_NOT_TKN", "not taken"}, {"RSLT_ADD_ARR", "to arrears"},
        {"RSLT_PAYBK", "payback"}, {"RSLT_XFER_VAL", "transferred"}
    };

    private final Db db;
    private final String emplid;
    private final String calRun;
    private final Integer rcd;

    /** pin number -> one text entry per stored result row. */
    final Map<Integer, List<String>> values = new LinkedHashMap<>();
    /** Full result rows for the root earning/deduction (when it has some). */
    final List<Row> rootRows = new ArrayList<>();

    ValueProbe(Db db, String emplid, String calRun, Integer rcd) {
        this.db = db;
        this.emplid = emplid;
        this.calRun = calRun;
        this.rcd = rcd;
    }

    void load(Collection<Integer> pinNums, int rootPin) {
        List<Integer> all = new ArrayList<>(pinNums);
        for (int i = 0; i < all.size(); i += 400) {
            List<Integer> chunk = all.subList(i, Math.min(all.size(), i + 400));
            String in = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            loadErnDed(chunk, in, rootPin);
            loadPin(chunk, in);
            loadAcum(chunk, in);
        }
    }

    private List<Object> params(List<Integer> chunk) {
        List<Object> p = new ArrayList<>();
        p.add(emplid);
        p.add(calRun);
        if (rcd != null) {
            p.add(rcd);
        }
        p.addAll(chunk);
        return p;
    }

    private String where(String in) {
        return " WHERE EMPLID = ? AND CAL_RUN_ID = ?" + (rcd != null ? " AND EMPL_RCD = ?" : "")
                + " AND PIN_NUM IN (" + in + ") ORDER BY PIN_NUM, SLICE_BGN_DT";
    }

    private static String slice(Row r) {
        String seg = r.integer("RSLT_SEG_NUM") > 1 ? " seg " + r.str("RSLT_SEG_NUM") : "";
        return "[" + r.str("SLICE_BGN_DT") + " .. " + r.str("SLICE_END_DT") + seg + "]";
    }

    private void add(int pin, String text) {
        values.computeIfAbsent(pin, k -> new ArrayList<>()).add(text);
    }

    private void loadPin(List<Integer> chunk, String in) {
        for (Row r : db.tryQuery("SELECT * FROM PS_GP_RSLT_PIN" + where(in), params(chunk).toArray())) {
            String v;
            if (!r.str("CHR_PIN_VAL").isEmpty()) {
                v = r.str("CHR_PIN_VAL");
            } else if (!r.isNull("DATE_PIN_VAL") && !r.str("DATE_PIN_VAL").startsWith("1900")) {
                v = r.str("DATE_PIN_VAL");
            } else {
                v = r.str("CALC_RSLT_VAL");
            }
            add(r.integer("PIN_NUM"), slice(r) + " " + v);
        }
    }

    private void loadAcum(List<Integer> chunk, String in) {
        for (Row r : db.tryQuery("SELECT * FROM PS_GP_RSLT_ACUM" + where(in), params(chunk).toArray())) {
            add(r.integer("PIN_NUM"), slice(r) + " " + r.str("CALC_RSLT_VAL"));
        }
    }

    private void loadErnDed(List<Integer> chunk, String in, int rootPin) {
        for (Row r : db.tryQuery("SELECT * FROM PS_GP_RSLT_ERN_DED" + where(in), params(chunk).toArray())) {
            StringBuilder sb = new StringBuilder(slice(r));
            for (String[] c : ERN_DED_COLUMNS) {
                BigDecimal v = r.num(c[0]);
                if (v.signum() != 0 || c[0].equals("CALC_RSLT_VAL")) {
                    sb.append(' ').append(c[1]).append('=').append(r.str(c[0]));
                }
            }
            add(r.integer("PIN_NUM"), sb.toString());
            if (r.integer("PIN_NUM") == rootPin) {
                rootRows.add(r);
            }
        }
    }

    /** Short annotation for the calculation tree (first few slices only). */
    String annotation(int pin) {
        List<String> v = values.get(pin);
        if (v == null || v.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, v.size()); i++) {
            if (i > 0) {
                sb.append("  |  ");
            }
            sb.append(v.get(i));
        }
        if (v.size() > 3) {
            sb.append("  | +").append(v.size() - 3).append(" more");
        }
        return sb.toString();
    }
}
