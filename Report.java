import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Prints the explanation: header, inputs, calculation and outputs of one element, read from the XML exports. */
final class Report {
    private static final int RULE_WIDTH = 100;
    private static final int MAX_CHILDREN = 25;
    private static final int MAX_LIST = 25;

    private final PrintStream out;
    private final XmlDb db;
    private final Pins pins;
    private final Codes codes;
    private final Loader loader;
    private final Options opt;

    Report(PrintStream out, XmlDb db, Pins pins, Codes codes, Loader loader, Options opt) {
        this.out = out;
        this.db = db;
        this.pins = pins;
        this.codes = codes;
        this.loader = loader;
        this.opt = opt;
    }

    void run(Pin root) {
        Graph g = new Graph(loader, pins, root, opt.inputDepth, opt.maxNodes);
        header(root);
        inputs(g, root);
        calculation(g);
        outputs(g);
    }

    // ------------------------------------------------------------------ layout helpers

    private void banner(String text) {
        out.println(Text.repeat("=", RULE_WIDTH));
        out.println(" " + text);
        out.println(Text.repeat("=", RULE_WIDTH));
    }

    private void section(String text) {
        out.println();
        out.println(Text.repeat("=", RULE_WIDTH));
        out.println(text);
        out.println(Text.repeat("=", RULE_WIDTH));
    }

    private void sub(String text) {
        out.println();
        out.println(text);
        out.println(Text.repeat("-", Math.min(RULE_WIDTH, text.length())));
    }

    private void kv(String label, String value) {
        if (value != null && !Text.isBlank(value)) {
            out.println(String.format("  %-22s: %s", label, value));
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Free-text comments with the "---- date author ----" stamp lines removed. */
    private List<String> commentLines(Pin p, int max) {
        List<String> res = new ArrayList<>();
        for (String l : p.comments.split("\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("-----")) {
                continue;
            }
            res.add(clip(t, 170));
            if (res.size() >= max) {
                break;
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ header

    private void header(Pin root) {
        banner(root.name + "  -  " + root.descr);
        out.println(" Type        : " + pins.typeName(root.type) + " (" + root.type + ")");
        out.println(" Element code: " + root.code + "   (PIN_NUM " + root.num + ")");
        out.println(" As-of date  : " + opt.asof + "   (effective-dated rules are read as of this date)");
        out.println(Text.repeat("=", RULE_WIDTH));
        Row r = root.row;
        String scope = codes.label("USED_BY", r.str("USED_BY")) + (r.str("COUNTRY").isEmpty() ? "" : " (" + r.str("COUNTRY") + ")");
        kv("Country scope", scope);
        kv("Value format", codes.label("FLD_FMT", r.str("FLD_FMT")));
        List<String> ov = new ArrayList<>();
        String[][] flags = {{"CAL", "calendar"}, {"PYENT", "pay entity"}, {"PG", "pay group"}, {"PYE", "payee"},
            {"PI", "positive input"}, {"ELEM", "element"}, {"SOVR", "supporting-element override"}};
        for (String[] f : flags) {
            if (r.yes("OVRD_IND_" + f[0])) {
                ov.add(f[1]);
            }
        }
        kv("Overridable at", ov.isEmpty() ? "nowhere (no override indicators set)" : String.join(", ", ov));
        if (!r.str("LAST_UPDT_OPRID").isEmpty()) {
            kv("Last updated", r.str("LAST_UPDT_DTTM") + " by " + r.str("LAST_UPDT_OPRID"));
        }
        List<String> notes = commentLines(root, 15);
        if (!notes.isEmpty()) {
            out.println("  Definition notes:");
            for (String n : notes) {
                out.println("    " + n);
            }
        }
    }

    // ------------------------------------------------------------------ 1. inputs

    private void inputs(Graph g, Pin root) {
        section("1. DATA INPUTS - what the calculation reads");
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (String k : new String[] {Loader.G_ARRAY, Loader.G_SYSTEM, Loader.G_BRACKET, Loader.G_PARAM,
            Loader.OTHER_GROUP}) {
            groups.put(k, new TreeSet<String>());
        }
        for (Node n : g.nodes.values()) {
            if (n.inputGroup != null) {
                Set<String> set = groups.get(n.inputGroup);
                if (set == null) {
                    set = new TreeSet<>();
                    groups.put(n.inputGroup, set);
                }
                set.addAll(n.inputLines);
                // An array loads many fields; list only those that fill a variable this calculation really uses.
                for (Map.Entry<Integer, String> vl : n.varLines) {
                    Node var = g.get(vl.getKey());
                    if (var != null && !"Y".equals(var.facts.get("scratch"))) {
                        groups.get(n.inputGroup).add(vl.getValue());
                    }
                }
            }
        }
        int total = 0;
        for (Map.Entry<String, Set<String>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            total += e.getValue().size();
            out.println();
            out.println(e.getKey() + ":");
            int i = 0;
            for (String l : e.getValue()) {
                if (i++ >= 60) {
                    out.println("   ... " + (e.getValue().size() - 60) + " more");
                    break;
                }
                out.println("   * " + clip(l, RULE_WIDTH - 6));
            }
        }
        if (total == 0) {
            out.println("  No external inputs found within the traversed dependencies.");
        }

        // Direct entry
        out.println();
        out.println("Direct entry / overrides:");
        Row r = root.row;
        if (r.yes("OVRD_IND_PI")) {
            out.println("   * Positive input is allowed for this element (values can be entered or generated per payee).");
        } else {
            out.println("   * Positive input is not enabled for this element.");
        }
        if (r.integer("PIN_DRIVER_NUM") != 0) {
            out.println("   * Driver element: " + pins.name(r.integer("PIN_DRIVER_NUM")));
        }
        if (g.truncatedByDepth || g.truncatedByCount) {
            out.println();
            out.println("NOTE: the dependency walk was cut short ("
                    + (g.truncatedByDepth ? "input depth " + opt.inputDepth + " reached" : "")
                    + (g.truncatedByDepth && g.truncatedByCount ? ", " : "")
                    + (g.truncatedByCount ? "max nodes " + opt.maxNodes + " reached" : "")
                    + "). Use --input-depth / --max-nodes to look further; the lists above may be incomplete.");
        }
        out.println();
        out.println("(" + g.nodes.size() + " elements analysed)");
    }

    // ------------------------------------------------------------------ 2. calculation

    private void calculation(Graph g) {
        Node root = g.root;
        section("2. HOW IT IS CALCULATED");

        sub("2.1 Where it runs in the payroll process");
        List<String> chains = new ArrayList<>(new LinkedHashSet<>(parentChains(root.pin.num, new HashSet<Integer>(), 0)));
        if (chains.isEmpty()) {
            out.println("  Not listed as a step in any section or process effective on " + opt.asof
                    + " (it may be referenced only through formulas, accumulators or element groups).");
        } else {
            int i = 0;
            for (String c : chains) {
                if (i++ >= 10) {
                    out.println("  ... " + (chains.size() - 10) + " more call paths");
                    break;
                }
                out.println("  " + clip(c, 400) + "  >  " + root.pin.name);
            }
            neighbours(root.pin.num);
        }

        sub("2.2 Definition");
        if (root.detail.isEmpty()) {
            out.println("  (no definition lines - this element type has no calculation setup of its own)");
        }
        for (String l : root.detail) {
            out.println("  " + l);
        }

        sub("2.3 Calculation chain - each input, what it is and how it is derived");
        out.println("  (depth " + opt.depth + ")");
        out.println();
        Set<Integer> printed = new HashSet<>();
        printed.add(root.pin.num);
        if (root.deps.isEmpty()) {
            out.println("  (no dependencies)");
        }
        printChildren(g, root, 1, printed);
    }

    private void printChildren(Graph g, Node parent, int level, Set<Integer> printed) {
        int shown = 0;
        for (Node.Dep d : parent.deps) {
            if (shown++ >= MAX_CHILDREN) {
                out.println(Text.repeat("     ", level - 1) + "  ... " + (parent.deps.size() - MAX_CHILDREN)
                        + " more inputs not shown");
                break;
            }
            Node child = g.get(d.pin);
            if (child == null) {
                out.println(Text.repeat("     ", level - 1) + "  - " + d.role + ": " + pins.name(d.pin) + " ["
                        + pins.typeName(pins.typeOf(d.pin)) + "]  (not loaded)");
                continue;
            }
            printNode(g, child, d.role, level, printed);
        }
    }

    private void printNode(Graph g, Node n, String role, int level, Set<Integer> printed) {
        Pin p = n.pin;
        String ind = Text.repeat("     ", level - 1) + "  ";
        StringBuilder h = new StringBuilder(ind + "- ");
        if (!role.isEmpty()) {
            h.append(role).append(": ");
        }
        h.append(p.name).append(" [").append(pins.typeName(p.type)).append("]");
        if (!p.descr.isEmpty() && !p.descr.equalsIgnoreCase(p.name)) {
            h.append(" - ").append(p.descr);
        }
        if (printed.contains(p.num)) {
            h.append("   (described above)");
            out.println(clip(h.toString(), 400));
            return;
        }
        printed.add(p.num);
        out.println(clip(h.toString(), 400));
        String di = ind + "    ";
        if (!p.type.equals("SY")) {
            for (String c : commentLines(p, 2)) {
                out.println(di + "# " + c);
            }
        }
        for (String l : n.detail) {
            out.println(di + l);
        }
        if (n.deps.isEmpty()) {
            return;
        }
        if (level < opt.depth) {
            printChildren(g, n, level + 1, printed);
        } else {
            out.println(di + "(" + n.deps.size() + " further input" + (n.deps.size() == 1 ? "" : "s")
                    + " not expanded - raise --depth)");
        }
    }

    /**
     * Parent rows (section steps / process steps) as of the as-of date: for every parent PIN_NUM only the rows of its
     * latest EFFDT on or before the as-of date are considered, then those whose column equals the element are kept.
     */
    private List<Row> effectiveParents(String table, String elemCol, int pin) {
        List<Row> latest = Table.effectiveLatest(db.rows(table), "PIN_NUM", opt.asof);
        return Table.eq(latest, elemCol, pin);
    }

    /** Which elements run immediately before/after the element in its section(s). */
    private void neighbours(int pin) {
        List<Row> parents = effectiveParents("PS_GP_SECTION_DTL", "PIN_ELEM_NUM", pin);
        for (Row p : parents) {
            List<Row> steps = loader.effRows("PS_GP_SECTION_DTL", "PIN_NUM", p.integer("PIN_NUM"), "SEQ_NUM5");
            int idx = -1;
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).integer("PIN_ELEM_NUM") == pin && steps.get(i).integer("SEQ_NUM5") == p.integer("SEQ_NUM5")) {
                    idx = i;
                }
            }
            if (idx < 0) {
                continue;
            }
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, idx - 3); i < Math.min(steps.size(), idx + 3); i++) {
                int el = steps.get(i).integer("PIN_ELEM_NUM");
                b.append(i == idx ? "[" + pins.name(el) + "]" : pins.name(el)).append(i < Math.min(steps.size(), idx + 3) - 1 ? " -> " : "");
            }
            out.println("  Order in section " + pins.name(p.integer("PIN_NUM")) + ": " + clip(b.toString(), 300));
        }
    }

    /** All call paths from process / top section down to the immediate parent of the given element. */
    private List<String> parentChains(int pin, Set<Integer> path, int depth) {
        List<String> res = new ArrayList<>();
        if (depth > 8) {
            return res;
        }
        List<Row> parents = new ArrayList<>(effectiveParents("PS_GP_SECTION_DTL", "PIN_ELEM_NUM", pin));
        parents.addAll(effectiveParents("PS_GP_PROCESS_DTL", "PIN_SECT_NUM", pin));
        for (Row r : parents) {
            int parent = r.integer("PIN_NUM");
            if (path.contains(parent)) {
                continue;
            }
            String label = pins.typeName(pins.typeOf(parent)) + " " + pins.name(parent) + " (step " + r.str("SEQ_NUM5") + ")";
            path.add(parent);
            List<String> up = parentChains(parent, path, depth + 1);
            path.remove(parent);
            if (up.isEmpty()) {
                res.add(label);
            } else {
                for (String u : up) {
                    res.add(u + "  >  " + label);
                }
            }
            if (res.size() > 40) {
                break;
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ 3. outputs

    /** The one sentence for section 3.1, built from STORE_RSLT and STORE_RSLT_IF_ZERO of the element row. */
    static String storedResultStatement(Row r) {
        if (!r.yes("STORE_RSLT")) {
            return "The result is not stored.";
        }
        return r.yes("STORE_RSLT_IF_ZERO")
                ? "The result is stored, and zero results are stored too."
                : "The result is stored; zero results are not stored.";
    }

    private void outputs(Graph g) {
        Node root = g.root;
        Pin p = root.pin;
        section("3. OUTPUTS - what it produces and who uses it");

        sub("3.1 Stored result");
        out.println("  " + storedResultStatement(p.row));

        if (!root.produces.isEmpty()) {
            Set<Integer> prod = new LinkedHashSet<>(root.produces);
            boolean own = prod.remove(p.num);
            if (own || !prod.isEmpty()) {
                sub("Values it hands on");
            }
            if (own) {
                out.println("  Returns its own value to whatever references it.");
            }
            if (!prod.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (int x : prod) {
                    names.add(pins.name(x) + " [" + pins.typeName(pins.typeOf(x)) + "]");
                }
                kv("Assigns", clip(String.join(", ", names), 400));
            }
        }

        sub("3.2 Accumulators it feeds");
        List<Row> feeds = Table.activeOn(Table.eq(db.rows("PS_GP_ACM_MBR"), "PIN_MBR_NUM", p.num), "BGN_DT", "END_DT", opt.asof);
        if (feeds.isEmpty()) {
            out.println("  None - the element is not a member of any accumulator on " + opt.asof + ".");
        }
        feeds = Table.sortBy(feeds, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return pins.name(a.integer("PIN_NUM")).toUpperCase().compareTo(pins.name(b.integer("PIN_NUM")).toUpperCase());
            }
        });
        for (Row f : feeds) {
            int acc = f.integer("PIN_NUM");
            String sign = "S".equals(f.str("ACM_SIGN")) ? "subtracted from" : "added to";
            String pct = f.num("PCT_TO_ACM").signum() == 0 || f.num("PCT_TO_ACM").intValue() == 100 ? "" : " at " + f.str("PCT_TO_ACM") + "%";
            out.println("  * " + sign + " " + pins.name(acc) + pct + "   - " + (pins.get(acc) == null ? "" : pins.get(acc).descr));
            Map<String, List<String>> users = usedBy(acc);
            for (Map.Entry<String, List<String>> e : users.entrySet()) {
                out.println("      " + e.getKey() + ": " + clip(String.join(", ", e.getValue()), 300));
            }
        }

        sub("3.3 Other elements that use it directly (any effective date)");
        Map<String, List<String>> direct = usedBy(p.num);
        if (direct.isEmpty()) {
            out.println("  No formulas, earnings, deductions or lookups reference this element directly.");
        }
        for (Map.Entry<String, List<String>> e : direct.entrySet()) {
            out.println("  " + e.getKey() + ": " + clip(String.join(", ", e.getValue()), 400));
        }
    }

    /** Elements that reference the given element, grouped by kind. Columns are discovered from the data. */
    private Map<String, List<String>> usedBy(int pin) {
        Map<String, List<String>> res = new LinkedHashMap<>();
        collect(res, "Earnings / deductions", "PS_GP_ERN_DED", refColumns("PS_GP_ERN_DED", "PIN_", null), pin);
        collect(res, "Formulas", "PS_GP_FORMULA_DTL", Arrays.asList("PIN_FRML_FLD1_NUM", "PIN_FRML_FLD2_NUM"), pin);
        collect(res, "Brackets (as lookup key)", "PS_GP_BRACKET", refColumns("PS_GP_BRACKET", "PIN_KEY", null), pin);
        collect(res, "Accumulators (as member)", "PS_GP_ACM_MBR", Arrays.asList("PIN_MBR_NUM", "PIN_PCT_NUM"), pin);
        collect(res, "Arrays (as key)", "PS_GP_ARRAY_KEY", Collections.singletonList("PIN_ELEM_NUM"), pin);
        collect(res, "Durations", "PS_GP_DURATION", Arrays.asList("PIN_DATE_FROM_NUM", "PIN_DATE_TO_NUM"), pin);
        return res;
    }

    private List<String> refColumns(String table, String prefix, String exclude) {
        List<String> res = new ArrayList<>();
        for (String c : db.columns(table)) {
            if (c.startsWith(prefix) && c.endsWith("_NUM") && !c.equals("PIN_NUM") && !c.equals(exclude)) {
                res.add(c);
            }
        }
        return res;
    }

    private void collect(Map<String, List<String>> res, String label, String table, List<String> columns, int pin) {
        if (columns.isEmpty()) {
            return;
        }
        Set<String> names = new TreeSet<>();
        for (Row r : Table.any(db.rows(table), pin, columns.toArray(new String[0]))) {
            int num = r.integer("PIN_NUM");
            if (num != pin) {
                names.add(pins.name(num));
            }
        }
        if (names.isEmpty()) {
            return;
        }
        List<String> list = new ArrayList<>(names);
        if (list.size() > MAX_LIST) {
            int more = list.size() - MAX_LIST;
            list = new ArrayList<>(list.subList(0, MAX_LIST));
            list.add("... +" + more + " more");
        }
        res.put(label + " (" + names.size() + ")", list);
    }
}
