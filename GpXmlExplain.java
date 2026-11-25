import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Explains any PeopleSoft Global Payroll element from XML exports of its definition tables: the data it
 * needs, how it is calculated and what it produces. Read-only; needs no database and only the JDK.
 *
 * Exit codes: 0 ok (or help), 1 option / directory / XML error, 2 element not found or ambiguous.
 */
public class GpXmlExplain {

    public static void main(String[] args) throws Exception {
        PrintStream console = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8");
        int code = run(args, console, err);
        console.flush();
        err.flush();
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Runs the tool and returns the exit code. Console output goes to {@code console}, errors to {@code err}. */
    static int run(String[] args, PrintStream console, PrintStream err) throws IOException {
        Options opt;
        try {
            opt = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println();
            err.print(Options.usage());
            return 1;
        }
        if (opt == null || (opt.element == null && opt.search == null)) {
            console.print(Options.usage());
            return 0;
        }

        File dir = new File(opt.xmlDir);
        if (!dir.isDirectory()) {
            err.println("Error: XML directory not found or not a directory: " + opt.xmlDir);
            return 1;
        }
        if (!new File(dir, "GP_PIN.xml").isFile()) {
            err.println("Error: GP_PIN.xml not found in XML directory " + opt.xmlDir);
            return 1;
        }

        // The report is built in memory first so nothing is shown or written when the run fails part way.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, "UTF-8");
        int code;
        try {
            code = explain(opt, new XmlDb(dir, opt.debug), out, err);
        } catch (XmlFormatException e) {
            // getMessage() already starts with the file name
            err.println("Error: malformed XML in " + e.getMessage());
            return 1;
        }
        out.flush();
        byte[] bytes = buffer.toByteArray();
        console.write(bytes, 0, bytes.length);
        console.flush();
        if (code == 0 && opt.outFile != null) {
            FileOutputStream f = new FileOutputStream(opt.outFile);
            try {
                f.write(bytes);
            } finally {
                f.close();
            }
        }
        return code;
    }

    private static int explain(Options opt, XmlDb db, PrintStream out, PrintStream err) {
        Pins pins = new Pins(db);
        Codes codes = new Codes(db);

        if (opt.search != null) {
            List<Pin> hits = new ArrayList<Pin>();
            List<String> wanted = opt.types == null ? null : Arrays.asList(opt.types.split(","));
            for (Pin p : pins.find(opt.search, Integer.MAX_VALUE)) {
                if (wanted == null || wanted.contains(p.type)) {
                    hits.add(p);
                }
            }
            if (hits.isEmpty()) {
                out.println("No elements match '" + opt.search + "'.");
                return 0;
            }
            if (hits.size() > 200) {
                out.println("(showing first 200 of " + hits.size() + " matches - narrow the search or use --type)");
                hits = hits.subList(0, 200);
            }
            for (Pin p : hits) {
                out.println(String.format("%-4s %-34s %s", p.type, p.name, p.descr));
            }
            return 0;
        }

        List<Pin> found = pins.find(opt.element, 40);
        if (found.isEmpty()) {
            out.println("No element found for '" + opt.element + "'. Try --search " + opt.element);
            return 2;
        }
        if (found.size() > 1) {
            out.println("'" + opt.element + "' matches " + found.size() + " elements. Re-run with the exact name, "
                    + "element code or PIN_NUM:");
            for (Pin p : found) {
                out.println(String.format("  %-4s %-34s PIN_NUM %-8d %s", p.type, p.name, p.num, p.descr));
            }
            return 2;
        }

        Setters setters = new Setters(db);
        Loader loader = new Loader(db, pins, codes, setters, opt.asof);
        new Report(out, db, pins, codes, loader, opt).run(found.get(0));
        return 0;
    }
}
