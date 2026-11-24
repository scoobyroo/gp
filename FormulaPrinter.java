import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the rows of PS_GP_FORMULA_DTL into readable pseudo-code and records which elements the
 * formula reads and which it assigns. Function / operator codes are the delivered GP codes
 * (translate field FRML_FUNC_OPTN / FRML_OPER1).
 */
final class FormulaPrinter {
    final List<String> lines = new ArrayList<>();
    final Set<Integer> reads = new LinkedHashSet<>();
    final Set<Integer> writes = new LinkedHashSet<>();

    private final Pins pins;
    private final Codes codes;
    private String pending;

    FormulaPrinter(Pins pins, Codes codes) {
        this.pins = pins;
        this.codes = codes;
    }

    void render(List<Row> rows) {
        int indent = 0;
        for (Row r : rows) {
            String f = r.str("FRML_FUNC_OPTN");
            String a = ref(r, 1);
            String b = ref(r, 2);
            String c = ref(r, 3);
            String op = r.str("FRML_OPER1");
            String lp = r.str("FRML_LEFT_PAREN");
            String rp = r.str("FRML_RIGHT_PAREN");
            switch (f) {
                case "/":
                    flush(indent);
                    add(indent, "// " + r.str("FM_COMMENT").replace("-----", "").trim());
                    break;
                case "I":
                    flush(indent);
                    add(indent, "IF " + cond(a, op, b));
                    break;
                case "A":
                    add(indent, "    AND " + cond(a, op, b));
                    break;
                case "O":
                    add(indent, "    OR " + cond(a, op, b));
                    break;
                case "T":
                    flush(indent);
                    add(indent, "THEN");
                    indent++;
                    statement(indent, "", a, op, b, c, lp, rp);
                    break;
                case "E":
                    flush(indent);
                    indent = Math.max(0, indent - 1);
                    add(indent, "ELSE");
                    indent++;
                    statement(indent, "", a, op, b, c, lp, rp);
                    break;
                case "F":
                    flush(indent);
                    indent = Math.max(0, indent - 1);
                    add(indent, "END IF");
                    break;
                case "Z":
                    flush(indent);
                    add(indent, "EXIT FORMULA");
                    break;
                default:
                    statement(indent, f, a, op, b, c, lp, rp);
                    break;
            }
        }
        flush(indent);
    }

    private void flush(int indent) {
        if (pending != null) {
            add(indent, pending + "    // (no target assigned)");
            pending = null;
        }
    }

    private void add(int indent, String text) {
        lines.add(Text.repeat("  ", indent) + text);
    }

    private String cond(String a, String op, String b) {
        if (b.isEmpty() && op.isEmpty()) {
            return a;
        }
        return a + " " + sym(op) + " " + b;
    }

    private String sym(String op) {
        switch (op) {
            case "G": return ">=";
            case "L": return "<=";
            case "^": return "<>";
            case "=": case "<": case ">": case "+": case "-": case "*": case "/": return op;
            case "": return "";
            default: return codes.label("FRML_OPER1", op);
        }
    }

    private String funcName(String f) {
        switch (f) {
            case "M": return "MAX";
            case "N": return "MIN";
            case "S": return "SUM";
            case "V": return "AVERAGE";
            case "X": return "IN";
            default: return codes.label("FRML_FUNC_OPTN", f).toUpperCase();
        }
    }

    private void statement(int indent, String f, String a, String op, String b, String c, String lp, String rp) {
        boolean hasA = !a.isEmpty();
        boolean hasB = !b.isEmpty();
        boolean hasC = !c.isEmpty();
        if (!hasA && !hasB && !hasC) {
            return;
        }
        String expr;
        if (!f.isEmpty() && !f.equals(" ")) {
            flush(indent);
            expr = funcName(f) + "(" + (hasA ? a : "") + (hasA && hasB ? ", " : "") + (hasB ? b : "") + ")";
        } else if (hasA) {
            flush(indent);
            expr = lp + a;
            if (hasB) {
                expr += (op.isEmpty() ? " " : " " + sym(op) + " ") + b + rp;
            } else {
                expr += rp;
            }
        } else if (hasB) {
            // Continuation of a running calculation started on an earlier row.
            if (pending == null) {
                expr = op.isEmpty() ? b : "<previous result> " + sym(op) + " " + lp + b + rp;
            } else {
                expr = pending + (op.isEmpty() ? " " : " " + sym(op) + " ") + lp + b + rp;
            }
        } else {
            expr = pending == null ? "<previous result>" : pending;
        }
        pending = null;
        if (hasC) {
            add(indent, c + " = " + expr);
        } else {
            pending = expr;
        }
    }

    private String ref(Row r, int i) {
        String fld = "FRML_FLD" + i;
        boolean hasValues = i < 3;
        Ref ref = Ref.of(r, "ENTRY_TYPE_FRML" + i, "PIN_FRML_FLD" + i + "_NUM",
                hasValues ? fld + "_CHAR_VAL" : null, hasValues ? fld + "_DEC_VAL" : null,
                hasValues ? fld + "_DATE_VAL" : null);
        if (ref.isEmpty()) {
            return "";
        }
        String s = ref.text(pins);
        if (ref.isPin() && "BR".equals(pins.typeOf(ref.pin))) {
            s = "LOOKUP(" + s + ")";   // evaluating a bracket performs the table lookup and fills its return variables
        }
        if (ref.isPin()) {
            if (i == 3) {
                writes.add(ref.pin);
            } else {
                reads.add(ref.pin);
            }
            if ("N".equals(r.str(fld + "_USE_OPTN"))) {
                s = "ELEMENT_NUMBER(" + s + ")";
            }
            if (r.yes(fld + "_OLDV_IND")) {
                s += "[prior value]";
            }
            if (r.yes(fld + "_ATRB_IND")) {
                s += "[attribute]";
            }
        }
        int rnd = r.integer("PIN_RND" + i + "_NUM");
        if (rnd != 0) {
            reads.add(rnd);
            s = "ROUND(" + s + ", " + pins.name(rnd) + ")";
        }
        return s;
    }
}
