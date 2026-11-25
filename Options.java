import java.time.LocalDate;

/** Command line settings. */
final class Options {
    static final String DEFAULT_XML_DIR = "D:\\kiro\\ai_db\\TEMP";

    String element;
    String search;
    String types;
    String xmlDir = DEFAULT_XML_DIR;
    String asof = LocalDate.now().toString();
    String outFile;
    int depth = 3;
    int inputDepth = 7;
    int maxNodes = 400;
    boolean debug;

    /** Parses the command line. Returns null when help was requested. */
    static Options parse(String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--asof": o.asof = value(args, ++i, a); break;
                case "--depth": o.depth = intValue(args, ++i, a); break;
                case "--input-depth": o.inputDepth = intValue(args, ++i, a); break;
                case "--max-nodes": o.maxNodes = intValue(args, ++i, a); break;
                case "--xml-dir": o.xmlDir = value(args, ++i, a); break;
                case "--out": o.outFile = value(args, ++i, a); break;
                case "--search": o.search = value(args, ++i, a); break;
                case "--type": o.types = value(args, ++i, a).toUpperCase(); break;
                case "--debug": o.debug = true; break;
                case "-h": case "--help": return null;
                default:
                    if (a.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option " + a);
                    }
                    if (o.element != null) {
                        throw new IllegalArgumentException("Only one element can be given (got '" + o.element + "' and '" + a + "')");
                    }
                    o.element = a;
            }
        }
        if (!o.asof.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("--asof must look like 2025-02-28");
        }
        if (o.depth < 1 || o.inputDepth < 1 || o.maxNodes < 1) {
            throw new IllegalArgumentException("--depth, --input-depth and --max-nodes must be positive");
        }
        return o;
    }

    private static String value(String[] args, int i, String name) {
        if (i >= args.length) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        return args[i];
    }

    private static int intValue(String[] args, int i, String name) {
        String v = value(args, i, name);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive whole number (got '" + v + "')");
        }
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "GpXmlExplain - explain how any PeopleSoft Global Payroll element (earning, deduction, ...) is calculated, from XML exports",
                "",
                "Usage: GpXmlExplain <element> [options]",
                "       GpXmlExplain --search <text>",
                "",
                "  <element>            element name (PIN_NM), element code (PIN_CODE) or PIN_NUM",
                "  --search <text>      list elements whose name/description contains the text",
                "  --type ER,DD         with --search: only these element types (ER earning, DD deduction, FM formula, ...)",
                "  --asof YYYY-MM-DD    read effective-dated rules as of this date (default: today)",
                "  --depth N            levels shown in the calculation chain (default 3)",
                "  --input-depth N      levels followed when collecting data inputs (default 7)",
                "  --max-nodes N        stop following dependencies after N elements (default 400)",
                "  --xml-dir <path>     directory with the XML exports (default " + DEFAULT_XML_DIR + ")",
                "  --out FILE           write the report to a file as well as the screen",
                "  --debug              print the file name and row count of each XML export as it is loaded",
                "  -h, --help           show this text",
                "");
    }
}
