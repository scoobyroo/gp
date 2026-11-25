import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reverse index answering "which elements assign a value to this variable?".
 * A variable can be filled by a formula (target field), a bracket (return column) or an
 * array (loaded field). The index is built lazily from the XML exports and cached.
 */
final class Setters {
    private final XmlDb db;
    private Map<Integer, Set<Integer>> index;

    Setters(XmlDb db) {
        this.db = db;
    }

    /** PIN_NUMs of the elements that set the given variable, in ascending order (empty when none). */
    Set<Integer> forVariable(int var) {
        if (index == null) {
            build();
        }
        Set<Integer> found = index.get(var);
        return found != null ? found : Collections.<Integer>emptySet();
    }

    private void build() {
        index = new HashMap<>();
        load("PS_GP_FORMULA_DTL", "PIN_FRML_FLD3_NUM");
        for (int n = 1; n <= 8; n++) {
            load("PS_GP_BRACKET", "PIN_VAL" + n + "_NUM");
        }
        load("PS_GP_ARRAY_FLD", "PIN_VAR_NUM");
    }

    /** Equivalent of: SELECT DISTINCT col AS V, PIN_NUM AS S FROM table WHERE col <> 0. */
    private void load(String table, String column) {
        List<Row> rows = db.rows(table);
        for (Row r : rows) {
            // SQL "col <> 0" drops both NULL and zero values
            if (r.num(column).signum() == 0) {
                continue;
            }
            int v = r.integer(column);
            int s = r.integer("PIN_NUM");
            if (v != s) {
                Set<Integer> setters = index.get(v);
                if (setters == null) {
                    setters = new TreeSet<>();
                    index.put(v, setters);
                }
                setters.add(s);
            }
        }
    }
}
