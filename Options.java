import java.time.LocalDate;

/** Command line settings. */
final class Options {
    String element;
    String search;
    String types;
    String config = "..\\wphrdev_connect\\config.xml";
    String schema;
    String asof = LocalDate.now().toString();
    String outFile;
    String emplid;
    String calRun;
    Integer rcd;
    int depth = 3;
    int inputDepth = 7;
    int maxNodes = 400;
    boolean debug;

    static Options parse(String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--asof": o.asof = value(args, ++i, a); break;
                case "--depth": o.depth = Integer.parseInt(value(args, ++i, a)); break;
                case "--input-depth": o.inputDepth = Integer.parseInt(value(args, ++i, a)); break;
                case "--max-nodes": o.maxNodes = Integer.parseInt(value(args, ++i, a)); break;
                case "--emplid": o.emplid = value(args, ++i, a); break;
                case "--calrun": o.calRun = value(args, ++i, a); break;
                case "--rcd": o.rcd = Integer.valueOf(value(args, ++i, a)); break;
                case "--config": o.config = value(args, ++i, a); break;
                case "--schema": o.schema = value(args, ++i, a); break;
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
        if ((o.emplid != null) != (o.calRun != null)) {
            throw new IllegalArgumentException("--emplid and --calrun must be used together");
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

    static String usage() {
        return String.join(System.lineSeparator(),
                "GpExplain - explain how any PeopleSoft Global Payroll element (earning, deduction, ...) is calculated",
                "",
                "Usage: GpExplain <element> [options]",
                "       GpExplain --search <text>",
                "",
                "  <element>            element name (PIN_NM), element code (PIN_CODE) or PIN_NUM",
                "  --search <text>      list elements whose name/description contains the text",
                "  --type ER,DD         with --search: only these element types (ER earning, DD deduction, FM formula, ...)",
                "  --asof YYYY-MM-DD    read effective-dated rules as of this date (default: today)",
                "  --depth N            levels shown in the calculation chain (default 3)",
                "  --input-depth N      levels followed when collecting data inputs (default 7)",
                "  --max-nodes N        stop following dependencies after N elements (default 400)",
                "  --emplid ID --calrun RUN [--rcd N]",
                "                       also show this payee's stored values from that calendar run",
                "  --config FILE        connection file with url/user/password (default ..\\wphrdev_connect\\config.xml)",
                "  --schema NAME        ALTER SESSION SET CURRENT_SCHEMA before querying (if tables are in another schema)",
                "  --out FILE           write the report to a file as well as the screen",
                "  --debug              print the SQL that is run",
                "");
    }
}
