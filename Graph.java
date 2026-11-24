import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Breadth-first walk of the dependency graph below an element. Nodes are loaded once and shared,
 * so an element used in many places is analysed a single time.
 */
final class Graph {
    final Map<Integer, Node> nodes = new LinkedHashMap<>();
    final Node root;
    boolean truncatedByDepth;
    boolean truncatedByCount;

    private final Loader loader;
    private final Pins pins;

    Graph(Loader loader, Pins pins, Pin rootPin, int maxDepth, int maxNodes) {
        this.loader = loader;
        this.pins = pins;
        this.root = loader.load(rootPin);
        root.depth = 0;
        nodes.put(rootPin.num, root);
        build(maxDepth, maxNodes);
    }

    private void build(int maxDepth, int maxNodes) {
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n.depth >= maxDepth) {
                if (!n.deps.isEmpty()) {
                    truncatedByDepth = true;
                }
                continue;
            }
            n.expanded = true;
            for (Node.Dep d : new ArrayList<>(n.deps)) {
                Node child = nodes.get(d.pin);
                if (child == null) {
                    Pin cp = pins.get(d.pin);
                    if (cp == null) {
                        continue;
                    }
                    if (nodes.size() >= maxNodes) {
                        truncatedByCount = true;
                        continue;
                    }
                    child = loader.load(cp);
                    child.depth = n.depth + 1;
                    nodes.put(cp.num, child);
                    queue.add(child);
                }
                // A candidate "set by" edge is only real if the setter really assigns the variable
                // in its current version (the setter index looks at all effective dates).
                if (d.setter && !child.produces.contains(n.pin.num)) {
                    n.deps.remove(d);
                }
            }
        }
    }

    Node get(int pin) {
        return nodes.get(pin);
    }
}
