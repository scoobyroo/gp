import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * Explains any PeopleSoft Global Payroll element from its definition tables: the data it needs,
 * how it is calculated and what it produces. Read-only; works for any installation because it only
 * relies on the delivered PS_GP_* record layouts and PSXLATITEM translate values.
 */
public class GpExplain {

    public static void main(String[] args) throws Exception {
        Options opt;
        try {
            opt = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.print(Options.usage());
            System.exit(1);
            return;
        }
        if (opt == null || (opt.element == null && opt.search == null)) {
            System.out.print(Options.usage());
            return;
        }

        PrintStream console = new PrintStream(new FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        PrintStream out = console;
        PrintStream file = null;
        if (opt.outFile != null) {
            file = new PrintStream(new FileOutputStream(opt.outFile), true, "UTF-8");
            out = new PrintStream(new Tee(console, file), true, "UTF-8");
        }

        try (Db db = new Db(opt.config, opt.schema, opt.debug)) {
            Pins pins = new Pins(db);
            Codes codes = new Codes(db);

            if (opt.search != null) {
                List<Pin> hits = new java.util.ArrayList<>();
                for (Pin p : pins.find(opt.search, 5000)) {
                    if (opt.types == null || java.util.Arrays.asList(opt.types.split(",")).contains(p.type)) {
                        hits.add(p);
                    }
                }
                if (hits.size() > 200) {
                    out.println("(showing first 200 of " + hits.size() + " matches - narrow the search or use --type)");
                    hits = hits.subList(0, 200);
                }
                if (hits.isEmpty()) {
                    out.println("No elements match '" + opt.search + "'.");
                }
                for (Pin p : hits) {
                    out.println(String.format("%-4s %-34s %s", p.type, p.name, p.descr));
                }
                return;
            }

            List<Pin> found = pins.find(opt.element, 40);
            if (found.isEmpty()) {
                out.println("No element found for '" + opt.element + "'. Try --search " + opt.element);
                System.exit(2);
            }
            if (found.size() > 1) {
                out.println("'" + opt.element + "' matches " + found.size() + " elements. Re-run with the exact name, "
                        + "element code or PIN_NUM:");
                for (Pin p : found) {
                    out.println(String.format("  %-4s %-34s PIN_NUM %-8d %s", p.type, p.name, p.num, p.descr));
                }
                System.exit(2);
            }

            Setters setters = new Setters(db);
            Loader loader = new Loader(db, pins, codes, setters, opt.asof);
            new Report(out, db, pins, codes, loader, opt).run(found.get(0));
        } finally {
            out.flush();
            if (file != null) {
                file.close();
            }
        }
    }

    /** Duplicates everything written to it onto two streams. */
    private static final class Tee extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        Tee(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) throws java.io.IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws java.io.IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() throws java.io.IOException {
            a.flush();
            b.flush();
        }
    }
}
