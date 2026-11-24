import java.math.BigDecimal;

/**
 * A reference stored in a GP definition table: either another element (pin number) or a literal
 * constant. PeopleSoft stores these as an ENTRY_TYPE code plus a pin number and/or constant columns.
 * Convention used by delivered tables: entry types 001/002/003 are character/number/date constants,
 * anything else with a pin number is an element reference.
 */
final class Ref {
    final int pin;
    final String constant; // already formatted for display, null when this is not a constant

    private Ref(int pin, String constant) {
        this.pin = pin;
        this.constant = constant;
    }

    static final Ref EMPTY = new Ref(0, null);

    boolean isEmpty() {
        return pin == 0 && constant == null;
    }

    boolean isPin() {
        return pin != 0;
    }

    /** Build from a row. Column names for the constant parts may be null when the table has none. */
    static Ref of(Row r, String entryTypeCol, String pinCol, String charCol, String decCol, String dateCol) {
        int pin = pinCol == null ? 0 : r.integer(pinCol);
        String et = entryTypeCol == null ? "" : r.str(entryTypeCol);
        if (pin != 0) {
            return new Ref(pin, null);
        }
        switch (et) {
            case "001":
                return charCol == null ? EMPTY : new Ref(0, "'" + r.str(charCol) + "'");
            case "002":
                if (decCol == null) {
                    return EMPTY;
                }
                BigDecimal d = r.num(decCol);
                return new Ref(0, d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
            case "003":
                return dateCol == null ? EMPTY : new Ref(0, r.str(dateCol).isEmpty() ? "<null date>" : r.str(dateCol));
            default:
                return EMPTY;
        }
    }

    String text(Pins pins) {
        if (pin != 0) {
            return pins.name(pin);
        }
        return constant == null ? "" : constant;
    }
}
