/** A Global Payroll element definition (one row of PS_GP_PIN). */
final class Pin {
    final int num;
    final String code;      // PIN_CODE, normally "<name> <country>"
    final String name;      // PIN_NM
    final String type;      // PIN_TYPE, e.g. ER, DD, FM, AC, VR ...
    final String descr;
    final String comments;
    final String country;
    final Row row;

    Pin(Row r) {
        this.row = r;
        this.num = r.integer("PIN_NUM");
        this.code = r.str("PIN_CODE");
        this.name = r.str("PIN_NM").isEmpty() ? r.str("PIN_CODE") : r.str("PIN_NM");
        this.type = r.str("PIN_TYPE");
        this.descr = r.str("DESCR");
        this.comments = r.str("COMMENTS").replace("\r", "");
        this.country = r.str("COUNTRY");
    }

    boolean isEarningOrDeduction() {
        return type.equals("ER") || type.equals("DD");
    }

    @Override
    public String toString() {
        return name + " [" + type + " #" + num + "]";
    }
}
