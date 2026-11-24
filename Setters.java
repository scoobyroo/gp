import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reverse index answering "which elements assign a value to this variable?".
 * A variable can be filled by a formula (target field), a bracket (return column) or an
 * array (loaded field). The index is built lazily with a handful of queries and cached.
 */
final class Setters {
    private final Db db;
    private Map<Integer, Set<Integer>> index;

    Setters(Db db) {
        this.db = db;
    }

    Set<Integer> forVariable(int var) {
        if (index == null) {
            build();
        }
        return index.getOrDefault(var, Collections.emptySet());
    }

    private void build() {
        index = new HashMap<>();
        load("SELECT DISTINCT PIN_FRML_FLD3_NUM AS V, PIN_NUM AS S FROM PS_GP_FORMULA_DTL WHERE PIN_FRML_FLD3_NUM <> 0");
        for (int n = 1; n <= 8; n++) {
            load("SELECT DISTINCT PIN_VAL" + n + "_NUM AS V, PIN_NUM AS S FROM PS_GP_BRACKET WHERE PIN_VAL" + n
                    + "_NUM <> 0");
        }
        load("SELECT DISTINCT PIN_VAR_NUM AS V, PIN_NUM AS S FROM PS_GP_ARRAY_FLD WHERE PIN_VAR_NUM <> 0");
    }

    private void load(String sql) {
        for (Row r : db.tryQuery(sql)) {
            int v = r.integer("V");
            int s = r.integer("S");
            if (v != s) {
                index.computeIfAbsent(v, k -> new TreeSet<>()).add(s);
            }
        }
    }
}
