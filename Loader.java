import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Reads the GP definition tables for one element and turns them into a Node: readable definition
 * lines, the elements it depends on and the elements it assigns. Which tables are consulted is
 * decided purely by the element type (PIN_TYPE) - nothing is specific to a particular element,
 * country or customer configuration. Optional tables that are missing in an installation are skipped.
 */
final class Loader {
    /** A variable assigned in more places than this is treated as a shared scratch variable. */
    static final int SETTER_LIMIT = 6;
    private static final int MEMBER_DETAIL_LIMIT = 40;
    private static final int MEMBER_WALK_LIMIT = 60;
    private static final int SECTION_LIMIT = 60;

    static final String G_ARRAY = "Data read from tables (arrays)";
    static final String G_SYSTEM = "System elements (supplied by the payroll engine)";
    static final String G_BRACKET = "Lookup tables (brackets)";
    static final String G_PARAM = "Parameters and constants (variables no rule assigns)";
    static final String OTHER_GROUP = "Other element inputs (durations, rate codes, historic rules, ...)";

    private static final Map<String, String> ROLE = new HashMap<>();

    static {
        ROLE.put("PIN_AMT_NUM", "amount");
        ROLE.put("PIN_RATE_NUM", "rate");
        ROLE.put("PIN_UNIT_NUM", "unit");
        ROLE.put("PIN_PCT_NUM", "percent");
        ROLE.put("PIN_BASE_NUM", "base");
        ROLE.put("PIN_RCP_NUM", "recipient");
        ROLE.put("PIN_GCTL_NUM", "generation control");
        ROLE.put("PIN_PYBK_DED_NUM", "payback deduction");
        ROLE.put("PIN_PRO_NUM", "proration rule");
        ROLE.put("PIN_PARTL_FRML_NUM", "partial-amount formula");
        ROLE.put("PIN_ARR_AMT_NUM", "arrears amount");
        ROLE.put("PIN_PRE_FRML_NUM", "pre-calculation formula");
        ROLE.put("PIN_POST_FRML_NUM", "post-calculation formula");
        ROLE.put("PIN_RND_RATE_NUM", "rate rounding rule");
        ROLE.put("PIN_RND_UNIT_NUM", "unit rounding rule");
        ROLE.put("PIN_RND_PCT_NUM", "percent rounding rule");
        ROLE.put("PIN_RND_BASE_NUM", "base rounding rule");
        ROLE.put("PIN_RND_RSLV_NUM", "result rounding rule");
        ROLE.put("PIN_DRIVER_NUM", "driver");
        ROLE.put("PIN_FRML_NUM", "formula");
        ROLE.put("PIN_ACM_FRML_NUM", "accumulator formula");
        ROLE.put("PIN_BASIS_DT_NUM", "basis date");
        ROLE.put("PIN_BGN_DT_NUM", "begin date");
        ROLE.put("PIN_BGN_MTH_NUM", "begin month");
        ROLE.put("PIN_BGN_DAY_NUM", "begin day");
        ROLE.put("PIN_DATE_FROM_NUM", "from date");
        ROLE.put("PIN_DATE_TO_NUM", "to date");
        ROLE.put("PIN_RND_NUM", "rounding rule");
    }

    private final Db db;
    private final Pins pins;
    private final Codes codes;
    private final Setters setters;
    private final String asof;

    Loader(Db db, Pins pins, Codes codes, Setters setters, String asof) {
        this.db = db;
        this.pins = pins;
        this.codes = codes;
        this.setters = setters;
        this.asof = asof;
    }

    Node load(Pin p) {
        Node n = new Node(p);
        genericRoles(n, p.row, "PIN_PARENT_NUM");
        switch (p.type) {
            case "ER":
            case "DD":
                earningOrDeduction(n);
                break;
            case "FM":
                formula(n);
                break;
            case "AC":
                accumulator(n);
                break;
            case "BR":
                bracket(n);
                break;
            case "AR":
                array(n);
                break;
            case "VR":
                variable(n);
                break;
            case "DR":
                duration(n);
                break;
            case "PO":
                proration(n);
                break;
            case "GC":
                generationControl(n);
                break;
            case "SE":
                section(n);
                break;
            case "SY":
                n.inputGroup = G_SYSTEM;
                n.inputLines.add(p.name + " - " + p.descr);
                break;
            default:
                n.inputGroup = OTHER_GROUP;
                n.inputLines.add(p.name + " (" + pins.typeName(p.type) + ") - " + p.descr);
                break;
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers

    List<Row> effRows(String table, String pinCol, int pin, String orderBy) {
        String sql = "SELECT d.* FROM " + table + " d WHERE d." + pinCol + " = ? AND d.EFFDT = "
                + "(SELECT MAX(x.EFFDT) FROM " + table + " x WHERE x." + pinCol + " = d." + pinCol
                + " AND x.EFFDT <= TO_DATE(?, 'YYYY-MM-DD'))" + (orderBy == null ? "" : " ORDER BY d." + orderBy);
        return db.tryQuery(sql, pin, asof);
    }

    static String roleName(String col) {
        String known = ROLE.get(col);
        if (known != null) {
            return known;
        }
        String s = col;
        if (s.startsWith("PIN_AA_")) {
            return "auto-assigned " + s.substring(7, s.length() - 4).toLowerCase().replace('_', ' ');
        }
        if (s.startsWith("PIN_")) {
            s = s.substring(4);
        }
        if (s.endsWith("_NUM")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.toLowerCase().replace('_', ' ');
    }

    /** Every non-zero PIN_*_NUM column of a definition row is a reference to another element. */
    private void genericRoles(Node n, Row r, String... skip) {
        Set<String> skipSet = new java.util.HashSet<>(java.util.Arrays.asList(skip));
        for (String col : r.columns()) {
            if (col.startsWith("PIN_") && col.endsWith("_NUM") && !col.equals("PIN_NUM") && !skipSet.contains(col)) {
                n.reads(roleName(col), r.integer(col));
            }
        }
    }

    private String named(Ref ref) {
        return ref.text(pins);
    }

    // ------------------------------------------------------------------ earnings / deductions

    private void earningOrDeduction(Node n) {
        Pin p = n.pin;
        List<Row> rs = effRows("PS_GP_ERN_DED", "PIN_NUM", p.num, null);
        if (rs.isEmpty()) {
            n.line("No PS_GP_ERN_DED definition is effective on " + asof);
            return;
        }
        Row e = rs.get(0);
        boolean active = "A".equals(e.str("EFF_STATUS"));
        n.kv("Definition effective", e.str("EFFDT") + (active ? "" : "   ** INACTIVE (status " + e.str("EFF_STATUS") + ") **"));

        String rule = e.str("CALC_RULE");
        n.kv("Calculation rule", codes.both("CALC_RULE", rule));
        String freq = codes.both("FREQUENCY_OPTN", e.str("FREQUENCY_OPTN"));
        if (!e.str("FREQUENCY_ID").isEmpty()) {
            freq += ", frequency " + e.str("FREQUENCY_ID");
        }
        n.kv("Frequency", freq);
        n.kv("Currency", e.str("CURRENCY_CD"));

        // Components used by the rule. Delivered rules: AM = amount, PB = base * percent,
        // RU = unit * rate, RP = unit * rate * percent.
        String[] used;
        String expr;
        switch (rule) {
            case "AM": used = new String[] {"AMT"}; expr = "{AMT}"; break;
            case "PB": used = new String[] {"BASE", "PCT"}; expr = "{BASE} x {PCT}"; break;
            case "RU": used = new String[] {"UNIT", "RATE"}; expr = "{UNIT} x {RATE}"; break;
            case "RP": used = new String[] {"UNIT", "RATE", "PCT"}; expr = "{UNIT} x {RATE} x {PCT}"; break;
            default: used = new String[0]; expr = codes.label("CALC_RULE", rule); break;
        }
        String[] all = {"AMT", "RATE", "UNIT", "PCT", "BASE"};
        String[] names = {"amount", "rate", "unit", "percent", "base"};
        for (int i = 0; i < all.length; i++) {
            String c = all[i];
            Ref ref = Ref.of(e, "ENTRY_TYPE_" + c, "PIN_" + c + "_NUM", null, "GP_" + c, null);
            boolean isUsed = java.util.Arrays.asList(used).contains(c);
            if (ref.isEmpty() && !isUsed) {
                continue;
            }
            String text = ref.isEmpty() ? "(not defined)" : named(ref);
            if (ref.isPin()) {
                text += "  (" + pins.typeName(pins.typeOf(ref.pin)) + ")";
            } else if (!ref.isEmpty()) {
                text += "  (constant)";
            }
            if (!isUsed) {
                text += "  [defined but not used by this rule]";
            }
            n.kv("Component: " + names[i], text);
            expr = expr.replace("{" + c + "}", ref.isEmpty() ? "?" : named(ref));
        }
        n.kv("Result per slice", expr + (rule.isEmpty() ? "" : "   (before proration / rounding)"));
        n.facts.put("uses", String.join(",", used));
        n.facts.put("rule", rule);
        n.facts.put("arrears", e.yes("ARR_ALLOW_IND") ? "Y" : "N");

        Ref rcp = Ref.of(e, "ENTRY_TYPE_RCP", "PIN_RCP_NUM", null, null, null);
        if (!rcp.isEmpty()) {
            n.kv("Recipient", named(rcp));
        } else {
            n.kv("Recipient", e.str("RECIPIENT_ID"));
        }
        int gctl = e.integer("PIN_GCTL_NUM");
        if (gctl != 0) {
            n.kv("Generation control", pins.name(gctl) + "  (decides whether the element is calculated at all)");
        }
        // Proration
        String pro = e.str("PRORATION_OPTN");
        String proText = codes.both("PRORATION_OPTN", pro);
        if (e.integer("PIN_PRO_NUM") != 0) {
            proText += ": " + pins.name(e.integer("PIN_PRO_NUM"));
        }
        n.kv("Proration", proText);
        // Rounding
        // Group components that share the same rounding setting so the line stays short.
        Map<String, List<String>> roundGroups = new java.util.LinkedHashMap<>();
        String[][] rounds = {{"RSLV", "result"}, {"RATE", "rate"}, {"UNIT", "unit"}, {"PCT", "percent"}, {"BASE", "base"}};
        for (String[] r : rounds) {
            String o = e.str("RND_OPTN_" + r[0]);
            if (o.isEmpty()) {
                continue;
            }
            String t = codes.label("RND_OPTN_RSLV", o);
            int rp = e.integer("PIN_RND_" + r[0] + "_NUM");
            if (rp != 0) {
                t += " (" + pins.name(rp) + ")";
            }
            roundGroups.computeIfAbsent(t, k -> new ArrayList<>()).add(r[1]);
        }
        StringJoiner rnd = new StringJoiner("; ");
        for (Map.Entry<String, List<String>> g : roundGroups.entrySet()) {
            rnd.add(g.getKey() + " [" + String.join(", ", g.getValue()) + "]");
        }
        n.kv("Rounding", rnd.toString());
        n.kv("Retro recalculation", codes.label("RTO_RECALC_OPTN", e.str("RTO_RECALC_OPTN")));
        if (e.yes("NET_PAY_VALID_IND")) {
            n.kv("Net pay validation", "Yes - amount is checked against available net pay");
        }
        if (e.yes("PART_AMT_ALLOW_IND")) {
            String t = "Allowed";
            if (e.integer("PIN_PARTL_FRML_NUM") != 0) {
                t += ", formula " + pins.name(e.integer("PIN_PARTL_FRML_NUM"));
            } else if (!e.str("GP_PART_FRML_OPTN").isEmpty()) {
                t += ", " + codes.label("GP_PART_FRML_OPTN", e.str("GP_PART_FRML_OPTN"));
            }
            n.kv("Partial amounts", t);
        }
        if (e.yes("ARR_ALLOW_IND")) {
            StringJoiner t = new StringJoiner("; ");
            t.add("Allowed");
            t.add("payback amount: " + codes.label("ARR_PYBK_AMT_OPTN", e.str("ARR_PYBK_AMT_OPTN")));
            Ref arr = Ref.of(e, "ENTRY_TYPE_ARR_AMT", "PIN_ARR_AMT_NUM", null, "ARR_PYBK_AMT", null);
            if (!arr.isEmpty()) {
                t.add("arrears amount: " + named(arr));
            }
            t.add("control: " + codes.label("ARR_PYBK_CNTL_OPTN", e.str("ARR_PYBK_CNTL_OPTN")));
            n.kv("Arrears", t.toString());
        }
        if (e.integer("PIN_PYBK_DED_NUM") != 0) {
            n.kv("Payback deduction", pins.name(e.integer("PIN_PYBK_DED_NUM")));
        }
        if (e.integer("PIN_PRE_FRML_NUM") != 0) {
            n.kv("Pre-calc formula", pins.name(e.integer("PIN_PRE_FRML_NUM")));
        }
        if (e.integer("PIN_POST_FRML_NUM") != 0) {
            n.kv("Post-calc formula", pins.name(e.integer("PIN_POST_FRML_NUM")));
        }
        if (p.type.equals("DD")) {
            n.kv("Deduction priority", e.str("GP_PRIORITY_NBR"));
            n.kv("Amount carried forward", codes.label("DED_XFER_OPTN", e.str("DED_XFER_OPTN")));
        }
        genericRoles(n, e);
        // Auto-assigned components (optional table)
        for (Row c : db.tryQuery("SELECT * FROM PS_GP_PIN_CMPNT WHERE PIN_NUM = ?", p.num)) {
            genericRoles(n, c);
        }
    }

    // ------------------------------------------------------------------ formulas

    private void formula(Node n) {
        List<Row> rows = effRows("PS_GP_FORMULA_DTL", "PIN_NUM", n.pin.num, "SEQ_NUM5");
        if (rows.isEmpty()) {
            n.line("No formula rows are effective on " + asof);
            return;
        }
        n.kv("Formula effective", rows.get(0).str("EFFDT"));
        FormulaPrinter fp = new FormulaPrinter(pins, codes);
        fp.render(rows);
        n.line("Logic:");
        for (String l : fp.lines) {
            n.line("    " + l);
        }
        for (int r : fp.reads) {
            n.reads("reads", r);
        }
        n.produces.addAll(fp.writes);
    }

    // ------------------------------------------------------------------ accumulators

    private void accumulator(Node n) {
        List<Row> rs = db.tryQuery("SELECT * FROM PS_GP_ACCUMULATOR WHERE PIN_NUM = ?", n.pin.num);
        if (!rs.isEmpty()) {
            Row a = rs.get(0);
            String per = codes.both("ACM_PRD_OPTN", a.str("ACM_PRD_OPTN"));
            if ("6".equals(a.str("ACM_PRD_OPTN"))) {
                per += ", " + a.str("CUSTOM_MTHS") + " months";
            }
            n.kv("Accumulates over", per);
            n.kv("Timing", codes.label("ACM_TIMING_OPTN", a.str("ACM_TIMING_OPTN")));
            n.kv("Applies to", codes.label("CALC_TYPE", a.str("CALC_TYPE")));
            String basis = codes.label("ACM_BASIS_OPTN", a.str("ACM_BASIS_OPTN"));
            if ("S".equals(a.str("ACM_BASIS_OPTN"))) {
                basis += ": " + named(Ref.of(a, "ENTRY_TYPE_BASIS", "PIN_BASIS_DT_NUM", null, null, "BASIS_DT"));
            }
            n.kv("Basis date", basis);
            String prd = a.str("ACM_PRD_OPTN");
            if (!prd.equals("1") && !prd.equals("2") && !prd.isEmpty()) {
                StringJoiner b = new StringJoiner(", ");
                b.add(codes.label("ACM_BGN_OPTN", a.str("ACM_BGN_OPTN")));
                if ("S".equals(a.str("ACM_BGN_OPTN"))) {
                    b.add("date " + named(Ref.of(a, "ENTRY_TYPE_DT", "PIN_BGN_DT_NUM", null, null, "BGN_DT")));
                } else {
                    b.add("month " + ("P".equals(a.str("BGN_MTH_OPTN")) ? pins.name(a.integer("PIN_BGN_MTH_NUM"))
                            : a.str("BGN_MTH")));
                    b.add("day " + ("P".equals(a.str("BGN_DAY_OPTN")) ? pins.name(a.integer("PIN_BGN_DAY_NUM"))
                            : a.str("BGN_DAY")));
                }
                n.kv("Period starts", b.toString());
            }
            n.kv("Retro correction", a.yes("CORR_RTO_IND") ? "Yes" : "No");
            if (a.integer("NUM_MTHS_AFTER_END") > 0) {
                n.kv("Months after end", a.str("NUM_MTHS_AFTER_END"));
            }
            genericRoles(n, a);
        }
        List<Row> mem = db.tryQuery("SELECT m.* FROM PS_GP_ACM_MBR m WHERE m.PIN_NUM = ? "
                + "AND NVL(m.BGN_DT, DATE '1900-01-01') <= TO_DATE(?, 'YYYY-MM-DD') "
                + "AND (m.END_DT IS NULL OR m.END_DT >= TO_DATE(?, 'YYYY-MM-DD'))", n.pin.num, asof, asof);
        mem.sort(Comparator.comparing((Row m) -> pins.name(m.integer("PIN_MBR_NUM")).toUpperCase()));
        n.line("Members (" + mem.size() + "):");
        int shown = 0;
        int walked = 0;
        for (Row m : mem) {
            int mp = m.integer("PIN_MBR_NUM");
            boolean subtract = "S".equals(m.str("ACM_SIGN"));
            Ref pct = Ref.of(m, "ENTRY_TYPE_PCT", "PIN_PCT_NUM", null, "PCT_TO_ACM", null);
            String pctText = "";
            if (pct.isPin()) {
                pctText = "  x " + named(pct) + "%";
                n.reads("member percent", pct.pin);
            } else {
                BigDecimal d = m.num("PCT_TO_ACM");
                if (d.signum() != 0 && d.compareTo(BigDecimal.valueOf(100)) != 0) {
                    pctText = "  @ " + d.stripTrailingZeros().toPlainString() + "%";
                }
            }
            if (shown < MEMBER_DETAIL_LIMIT) {
                n.line("  " + (subtract ? "- " : "+ ") + pins.name(mp) + " (" + pins.typeName(pins.typeOf(mp)) + ")"
                        + pctText);
                shown++;
            }
            if (walked < MEMBER_WALK_LIMIT) {
                n.reads(subtract ? "subtracts" : "adds", mp);
                walked++;
            }
        }
        if (mem.size() > shown) {
            n.line("  ... " + (mem.size() - shown) + " more members not listed");
        }
        if (mem.isEmpty()) {
            n.line("  (no members effective on " + asof + ")");
        }
    }

    // ------------------------------------------------------------------ brackets

    private static String bracketValue(Row r, String prefix, int i) {
        String s = r.str(prefix + i);
        if (!s.isEmpty()) {
            return s;
        }
        if (!r.isNull(prefix + i + "_DT")) {
            return r.str(prefix + i + "_DT");
        }
        return r.str(prefix + i + "_DEC");
    }

    private void bracket(Node n) {
        List<Row> br = effRows("PS_GP_BRACKET", "PIN_NUM", n.pin.num, null);
        if (br.isEmpty()) {
            n.line("No bracket definition is effective on " + asof);
            return;
        }
        Row b = br.get(0);
        n.kv("Bracket effective", b.str("EFFDT"));
        int kc = Math.min(5, b.integer("KEY_COUNT"));
        int vc = Math.min(8, b.integer("VAL_RETURN_COUNT"));
        List<String> keyNames = new ArrayList<>();
        List<String> valNames = new ArrayList<>();
        for (int i = 1; i <= kc; i++) {
            Ref k = Ref.of(b, "ENTRY_TYPE_KEY" + i, "PIN_KEY" + i + "_NUM", null, null, null);
            keyNames.add(k.isEmpty() ? "?" : named(k));
            if (k.isPin()) {
                n.reads("lookup key " + i, k.pin);
            }
        }
        for (int i = 1; i <= vc; i++) {
            Ref v = Ref.of(b, "ENTRY_TYPE_VAL" + i, "PIN_VAL" + i + "_NUM", null, null, null);
            valNames.add(v.isEmpty() ? "?" : named(v));
            if (v.isPin()) {
                n.produces.add(v.pin);
            }
        }
        n.kv("Lookup keys", String.join(", ", keyNames));
        n.kv("Returns", String.join(", ", valNames));
        if (!b.str("INTERPOLATE_METHOD").isEmpty()) {
            n.kv("Between rows", codes.label("INTERPOLATE_METHOD", b.str("INTERPOLATE_METHOD")));
        }
        if (b.yes("LOW_HIGH_OPTION")) {
            n.kv("Low/high option", "Yes");
        }
        n.kv("If no match", codes.label("PROCESS_OPTION", b.str("PROCESS_OPTION")));
        if (b.yes("USE_FIRST_ROW")) {
            n.kv("Below first row", "uses first row");
        }
        if (b.yes("USE_LAST_ROW")) {
            n.kv("Above last row", "uses last row");
        }
        if (b.integer("PIN_RND_NUM") != 0) {
            n.reads("rounding rule", b.integer("PIN_RND_NUM"));
        }
        Object eff = b.raw("EFFDT");
        int count = 0;
        List<Row> c = db.tryQuery("SELECT COUNT(*) C FROM PS_GP_BRACKET_DTL WHERE PIN_NUM = ? AND EFFDT = ?", n.pin.num, eff);
        if (!c.isEmpty()) {
            count = c.get(0).integer("C");
        }
        n.kv("Table rows", String.valueOf(count));
        List<Row> sample = db.tryQuery("SELECT * FROM (SELECT d.* FROM PS_GP_BRACKET_DTL d WHERE d.PIN_NUM = ? "
                + "AND d.EFFDT = ? ORDER BY d.SEQ_NUM5) WHERE ROWNUM <= 4", n.pin.num, eff);
        for (Row s : sample) {
            StringJoiner ks = new StringJoiner(", ");
            for (int i = 1; i <= kc; i++) {
                ks.add(bracketValue(s, "DATA_KEY", i));
            }
            StringJoiner vs = new StringJoiner(", ");
            for (int i = 1; i <= vc; i++) {
                vs.add(bracketValue(s, "DATA_VAL", i));
            }
            n.line("  e.g. " + ks + "  ->  " + vs);
        }
        n.inputGroup = G_BRACKET;
        n.inputLines.add(n.pin.name + ": keys (" + String.join(", ", keyNames) + ") -> returns ("
                + String.join(", ", valNames) + "), " + count + " rows - " + n.pin.descr);
    }

    // ------------------------------------------------------------------ arrays (database records)

    private void array(Node n) {
        List<Row> ar = db.tryQuery("SELECT * FROM PS_GP_ARRAY WHERE PIN_NUM = ?", n.pin.num);
        if (ar.isEmpty()) {
            n.line("No PS_GP_ARRAY definition found");
            return;
        }
        Row a = ar.get(0);
        String rec = a.str("RECNAME");
        n.kv("Source record", rec);
        n.kv("Row selection", codes.label("ARRAY_KEY_OPTN", a.str("ARRAY_KEY_OPTN")));
        n.kv("Processing", codes.label("ARRAY_PRC_OPTN", a.str("ARRAY_PRC_OPTN")));
        genericRoles(n, a);
        for (Row k : db.tryQuery("SELECT * FROM PS_GP_ARRAY_KEY WHERE PIN_NUM = ? ORDER BY SEQ_NUM5", n.pin.num)) {
            Ref v = Ref.of(k, "ENTRY_TYPE_ID", "PIN_ELEM_NUM", null, null, null);
            String field = k.str("FIELDNAME").isEmpty() ? codes.label("ARRAY_KEY_TYPE", k.str("ARRAY_KEY_TYPE"))
                    : k.str("FIELDNAME");
            String text = "  key: " + field + " " + codes.label("ARRAY_KEY_OPER", k.str("ARRAY_KEY_OPER"))
                    + (v.isEmpty() ? "" : " " + named(v)) + (k.yes("USE_MAX_IND") ? " (latest row)" : "");
            n.line(text);
            if (v.isPin()) {
                n.reads("array key", v.pin);
            }
        }
        n.inputGroup = G_ARRAY;
        for (Row f : db.tryQuery("SELECT * FROM PS_GP_ARRAY_FLD WHERE PIN_NUM = ? ORDER BY SEQ_NUM5", n.pin.num)) {
            int var = f.integer("PIN_VAR_NUM");
            String line = rec + "." + f.str("FIELDNAME") + " -> " + pins.name(var);
            n.line("  loads: " + line + " (" + codes.label("ARRAY_FLD_USE", f.str("ARRAY_FLD_USE")) + ")");
            if (var != 0) {
                n.produces.add(var);
                n.varLines.add(new java.util.AbstractMap.SimpleEntry<>(var, line));
            }
        }
    }

    // ------------------------------------------------------------------ variables

    private void variable(Node n) {
        Pin p = n.pin;
        List<Row> v = effRows("PS_GP_VARIABLE", "PIN_NUM", p.num, null);
        String def = "";
        if (!v.isEmpty()) {
            Row r = v.get(0);
            switch (p.row.str("FLD_FMT")) {
                case "1": def = r.str("CHARACTER_VALUE"); break;
                case "2": def = r.str("VARIABLE_DATE"); break;
                case "3": def = r.str("MONETARY_VALUE") + " " + r.str("CURRENCY_CD"); break;
                default: def = r.str("DECIMAL_VALUE"); break;
            }
            def = def.trim();
            n.kv("Stored value", def.isEmpty() ? "(blank)" : def);
        }
        Set<Integer> cand = new java.util.TreeSet<>(setters.forVariable(p.num));
        cand.remove(p.num);
        if (cand.isEmpty()) {
            n.line("Not assigned by any formula, bracket or array - a constant / externally supplied value");
            n.inputGroup = G_PARAM;
            n.inputLines.add(p.name + (v.isEmpty() ? "" : " = " + (def.isEmpty() ? "(blank)" : def))
                    + " - " + p.descr);
        } else if (cand.size() > SETTER_LIMIT) {
            n.line("Assigned in " + cand.size() + " places (shared scratch variable) - not expanded");
            n.facts.put("scratch", "Y");
        } else {
            for (int s : cand) {
                n.setBy(s);
            }
        }
    }

    // ------------------------------------------------------------------ misc element types

    private void duration(Node n) {
        List<Row> d = effRows("PS_GP_DURATION", "PIN_NUM", n.pin.num, null);
        n.inputGroup = OTHER_GROUP;
        n.inputLines.add(n.pin.name + " (Duration) - " + n.pin.descr);
        if (d.isEmpty()) {
            return;
        }
        Row r = d.get(0);
        Ref from = Ref.of(r, "ENTRY_TYPE_FROM", "PIN_DATE_FROM_NUM", null, null, "DATE_FROM");
        Ref to = Ref.of(r, "ENTRY_TYPE_TO", "PIN_DATE_TO_NUM", null, null, "DATE_TO");
        n.kv("From", named(from));
        n.kv("To", named(to));
        n.kv("Measured in", codes.label("PERIOD_OPTN", r.str("PERIOD_OPTN")));
        genericRoles(n, r);
    }

    private void proration(Node n) {
        List<Row> d = effRows("PS_GP_PRORATION", "PIN_NUM", n.pin.num, null);
        n.inputGroup = OTHER_GROUP;
        n.inputLines.add(n.pin.name + " (Proration Rule) - " + n.pin.descr);
        if (d.isEmpty()) {
            return;
        }
        Row r = d.get(0);
        Ref num = Ref.of(r, "ENTRY_TYPE_ELEM", "PIN_NUMERATOR_NUM", null, null, null);
        Ref den = Ref.of(r, "ENTRY_TYPE_ELEM2", "PIN_DENOMIN_NUM", null, null, null);
        n.kv("Proration factor", named(num) + " / " + (r.yes("DENOM_USES_NUMER") ? "(numerator basis) " : "") + named(den));
        genericRoles(n, r);
    }

    private void generationControl(Node n) {
        List<Row> g = effRows("PS_GP_GCTL", "PIN_NUM", n.pin.num, null);
        // Each generation-control category has an Include/Exclude mode in PS_GP_GCTL and its values in PS_GP_GCTL_DTL.
        Map<String, String> modeField = new HashMap<>();
        modeField.put("ST", "GCTL_OPTN_EMPLSTAT");
        modeField.put("AR", "GCTL_OPTN_ACTION");
        modeField.put("SS", "GCTL_OPTN_SEGSTAT");
        modeField.put("FQ", "GCTL_OPTN_FREQ");
        modeField.put("FM", "GCTL_OPTN_FRML");
        modeField.put("RT", "GCTL_OPTN_RUN_TYPE");
        Map<String, StringJoiner> byType = new java.util.LinkedHashMap<>();
        for (Row d : effRows("PS_GP_GCTL_DTL", "PIN_NUM", n.pin.num, null)) {
            String type = d.str("GCTL_TYPE");
            String val;
            switch (type) {
                case "FM": val = pins.name(d.integer("PIN_FRML_NUM")); n.reads("generation formula", d.integer("PIN_FRML_NUM")); break;
                case "SS": val = d.str("SEG_STATUS"); break;
                case "FQ": val = d.str("FREQUENCY_TAG"); break;
                case "RT": val = d.str("RUN_TYPE"); break;
                case "AR": val = d.str("ACTION") + (d.str("ACTION_REASON").isEmpty() ? "" : "/" + d.str("ACTION_REASON")); break;
                default: val = d.str("EMPL_STATUS"); break;
            }
            String mode = g.isEmpty() || modeField.get(type) == null ? ""
                    : codes.label(modeField.get(type), g.get(0).str(modeField.get(type)));
            String key = codes.label("GCTL_TYPE", type) + (mode.isEmpty() ? "" : " (" + mode + ")");
            byType.computeIfAbsent(key, k -> new StringJoiner(", ")).add(val);
        }
        if (byType.isEmpty()) {
            n.line("No generation-control criteria defined - always generated");
        }
        for (Map.Entry<String, StringJoiner> e : byType.entrySet()) {
            n.kv(e.getKey(), e.getValue().toString());
        }
    }

    private void section(Node n) {
        List<Row> s = effRows("PS_GP_SECTION", "PIN_NUM", n.pin.num, null);
        if (!s.isEmpty()) {
            n.kv("Section type", codes.label("SECTION_TYPE_OPTN", s.get(0).str("SECTION_TYPE_OPTN")));
        }
        List<Row> d = effRows("PS_GP_SECTION_DTL", "PIN_NUM", n.pin.num, "SEQ_NUM5");
        n.line("Steps in run order (" + d.size() + "):");
        int i = 0;
        for (Row r : d) {
            int el = r.integer("PIN_ELEM_NUM");
            String cond = r.integer("PIN_FRML_NUM") != 0 ? "  [only if " + pins.name(r.integer("PIN_FRML_NUM")) + "]" : "";
            String loop = "N".equals(r.str("LOOP_ACTION")) || r.str("LOOP_ACTION").isEmpty() ? ""
                    : "  [" + codes.label("LOOP_ACTION", r.str("LOOP_ACTION")) + "]";
            if (i < SECTION_LIMIT) {
                n.line("  " + String.format("%5s", r.str("SEQ_NUM5")) + "  " + pins.name(el) + " ("
                        + pins.typeName(pins.typeOf(el)) + ")" + cond + loop);
                n.reads("section step", el);
                n.reads("step condition", r.integer("PIN_FRML_NUM"));
            }
            i++;
        }
        if (d.size() > SECTION_LIMIT) {
            n.line("  ... " + (d.size() - SECTION_LIMIT) + " more steps not listed");
        }
    }
}
