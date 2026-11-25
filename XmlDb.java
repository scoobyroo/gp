import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory table source backed by XML exports in one directory. Replaces the database connection.
 * Each export is parsed lazily, at most once, and the rows are cached by table name.
 * Table names map as PS_GP_XXX to GP_XXX.xml and PSXLATITEM to PSXLATITEM.xml.
 */
final class XmlDb {
    private final File dir;
    private final boolean debug;
    private final Map<String, List<Row>> cache = new HashMap<>();
    private final Map<String, List<String>> columnsCache = new HashMap<>();

    XmlDb(File xmlDir, boolean debug) {
        this.dir = xmlDir;
        this.debug = debug;
    }

    /**
     * All rows of a table as an unmodifiable list. Parses on the first call and returns the same list afterwards.
     * Returns an empty list when the export file does not exist.
     *
     * @throws XmlFormatException when the export is not well-formed
     */
    List<Row> rows(String table) {
        List<Row> cached = cache.get(table);
        if (cached != null) {
            return cached;
        }
        List<Row> result;
        File f = fileFor(table);
        if (!f.isFile()) {
            result = Collections.emptyList();
        } else {
            List<Row> parsed = XmlReader.read(f);
            if (debug) {
                System.err.println("[xml] " + f.getName() + " rows=" + parsed.size());
            }
            result = Collections.unmodifiableList(parsed);
        }
        cache.put(table, result);
        return result;
    }

    /** True when the export file for the table exists in the XML directory. */
    boolean fileExists(String table) {
        return fileFor(table).isFile();
    }

    /** Column names of the table, taken from its first row; empty when the table has no rows. */
    List<String> columns(String table) {
        List<String> cached = columnsCache.get(table);
        if (cached != null) {
            return cached;
        }
        List<Row> r = rows(table);
        List<String> cols = r.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(r.get(0).columns()));
        columnsCache.put(table, cols);
        return cols;
    }

    private File fileFor(String table) {
        String base = table.startsWith("PS_") ? table.substring(3) : table;
        return new File(dir, base + ".xml");
    }
}
