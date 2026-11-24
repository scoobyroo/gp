import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** An element plus everything the explainer learned about how it is defined. */
final class Node {

    /** A dependency edge: this element needs (reads) the other element, in the given role. */
    static final class Dep {
        final String role;
        final int pin;
        final boolean setter; // true for "this variable is assigned by <pin>" edges

        Dep(String role, int pin, boolean setter) {
            this.role = role;
            this.pin = pin;
            this.setter = setter;
        }
    }

    final Pin pin;
    final List<String> detail = new ArrayList<>();   // human readable definition lines
    final List<Dep> deps = new ArrayList<>();         // elements needed to calculate this one
    final Set<Integer> produces = new LinkedHashSet<>(); // elements this one assigns / returns
    final java.util.Map<String, String> facts = new java.util.HashMap<>(); // machine readable facts (rule, ...)
    String inputGroup;                                // non-null when this node counts as a data input
    final List<String> inputLines = new ArrayList<>();
    /** For arrays: one entry per loaded field, keyed by the variable it fills (only shown if that variable is used). */
    final List<java.util.Map.Entry<Integer, String>> varLines = new ArrayList<>();
    int depth;
    boolean expanded;

    Node(Pin pin) {
        this.pin = pin;
    }

    void line(String text) {
        detail.add(text);
    }

    void kv(String label, String value) {
        if (value != null && !Text.isBlank(value)) {
            detail.add(String.format("%-22s: %s", label, value));
        }
    }

    void reads(String role, int pinNum) {
        if (pinNum != 0 && pinNum != pin.num) {
            for (Dep d : deps) {
                if (d.pin == pinNum && d.role.equals(role)) {
                    return;
                }
            }
            deps.add(new Dep(role, pinNum, false));
        }
    }

    void setBy(int pinNum) {
        deps.add(new Dep("set by", pinNum, true));
    }
}
