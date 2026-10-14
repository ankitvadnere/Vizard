package com.vizard.execution.insight;

import com.vizard.api.dto.trace.FrameSnapshot;
import com.vizard.api.dto.trace.GraphEdge;
import com.vizard.api.dto.trace.GraphInsight;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;
import com.vizard.api.dto.trace.VertexValue;
import com.vizard.execution.insight.CodeModel.GraphSpec;
import com.vizard.execution.insight.ExpressionEvaluator.HeapRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rebuilds the graph and the traversal state at every step, from the heap and the variables the
 * {@link GraphSpec} names. Steps are processed in order; the visit order and the tree edges are
 * the only things accumulated, and each step gets its own copy, so stepping back rewinds them.
 *
 * <p>Where each fact comes from:
 * <ul>
 *   <li>edges: the adjacency list / map / matrix object, read as it is <i>now</i></li>
 *   <li>visited, distances, parent: the arrays/sets the AST identified</li>
 *   <li>frontier: the queue/stack/priority queue's current elements</li>
 *   <li>current vertex u and the edge being examined: the variables of the running method</li>
 *   <li>tree edges: the parent array if there is one; otherwise the vertex u that set v's distance,
 *       or that was running when v was marked visited, or that pushed v (iterative DFS)</li>
 * </ul>
 */
final class GraphTracker {

    private static final long INFINITY_THRESHOLD = 1_000_000_000L;

    private final List<TraceStep> steps;
    private final List<GraphSpec> specs;
    private final Map<Long, Boolean> directedByGraph = new HashMap<>();
    private final Map<Long, Boolean> weightedByGraph = new HashMap<>();

    // Accumulated state.
    private Binding last;
    private Long orderFor;                                        // visited/graph id the order belongs to
    private final List<String> order = new ArrayList<>();
    private final Map<String, String> treeParent = new LinkedHashMap<>();
    private final Map<String, String> pusher = new HashMap<>();
    private Set<String> previousVisited = Set.of();
    private Map<String, String> previousDist = Map.of();
    private List<String> previousFrontier = List.of();
    private String previousCurrent;
    private boolean previousInBody;

    /** Counter increments produced by the last {@link #track} call. */
    int newRelaxations;

    /** The spec and heap objects in use at one step; frame = index of the method running the spec, or -1. */
    private record Binding(GraphSpec spec, int frame, Long graph, Long visited, Long dist, Long parent,
                           Long frontier) {
    }

    /** One graph as read from the heap: vertices and outgoing edges per vertex. */
    private record Graph(String representation, List<String> vertices, Map<String, List<GraphEdge>> out,
                         boolean truncated) {

        int edgeCount() {
            return out.values().stream().mapToInt(List::size).sum();
        }
    }

    GraphTracker(List<TraceStep> steps, List<GraphSpec> specs) {
        this.steps = steps;
        this.specs = specs;
        decideShapes();
    }

    boolean active() {
        return !specs.isEmpty();
    }

    /** The specs whose neighbour loop is in the method running at this step (for counting edges). */
    boolean isNeighbourLoop(TraceStep step, int headerLine) {
        if (step.stack().isEmpty()) {
            return false;
        }
        String method = step.stack().get(0).methodName();
        for (GraphSpec s : specs) {
            if (s.loopLine() == headerLine && s.method().equals(method) && s.methodContains(step.line())) {
                return true;
            }
        }
        return false;
    }

    /** The graph insight for step k (null if no graph is reachable), updating the accumulated state. */
    GraphInsight track(int k) {
        newRelaxations = 0;
        TraceStep step = steps.get(k);
        Binding binding = bind(step);
        if (binding == null || binding.graph() == null) {
            return null;
        }
        HeapObjectSnapshot graphObject = step.heap().get(Long.toString(binding.graph()));
        if (graphObject == null) {
            return null;
        }
        GraphSpec spec = binding.spec();
        Graph graph = read(graphObject, spec, step.heap());
        if (graph == null) {
            return null;
        }
        boolean directed = directedByGraph.getOrDefault(binding.graph(), true);
        boolean weighted = weightedByGraph.getOrDefault(binding.graph(), false);

        String current = binding.frame() >= 0 ? label(lookup(step, binding.frame(), spec.vertex())) : null;
        if (current != null && !graph.vertices().contains(current)) {
            current = null; // e.g. a for loop's u = n after its last iteration
        }
        boolean inBody = binding.frame() == 0 && spec.bodyContains(step.line());
        GraphEdge examining = null;
        if (inBody && current != null) {
            examining = neighbourEdge(step, spec, current);
        }

        List<String> visited = visited(step, binding.visited(), graph);
        Map<String, String> dist = distances(step, binding.dist(), graph);
        List<String> frontier = frontier(step, binding.frontier(), spec);

        // Order and tree edges accumulate for one traversal; a new visited array starts a new one.
        // (While the visited array is not declared yet, e.g. on the method's first line, keep the old order.)
        Long orderKey = binding.visited() != null ? binding.visited()
                : spec.visited() != null ? orderFor : binding.graph();
        if (orderKey != null && !Objects.equals(orderKey, orderFor)) {
            orderFor = orderKey;
            order.clear();
            treeParent.clear();
            pusher.clear();
            previousVisited = Set.of();
            previousDist = Map.of();
            previousFrontier = List.of();
        }
        updatePushers(frontier, inBody);
        updateFromDistances(dist);
        List<String> newlyVisited = new ArrayList<>();
        if (binding.visited() != null) {
            for (String v : visited) {
                if (!previousVisited.contains(v)) {
                    newlyVisited.add(v);
                }
            }
            previousVisited = new HashSet<>(visited);
        } else if (spec.visited() == null && (spec.frontier() != null || spec.dist() != null)
                && current != null && !order.contains(current)) {
            // No visited array (heap Dijkstra, Prim, topological sort): a vertex counts once it is processed.
            newlyVisited.add(current);
        }
        for (String v : newlyVisited) {
            if (!order.contains(v)) {
                order.add(v);
            }
            if (!treeParent.containsKey(v)) {
                String from = discoverer(step, spec, v);
                if (from == null) {
                    from = pusher.get(v);
                }
                if (from != null && !from.equals(v)) {
                    treeParent.put(v, from);
                }
            }
        }
        previousDist = dist;
        previousFrontier = frontier;
        previousCurrent = current;
        previousInBody = inBody;

        List<GraphEdge> treeEdges = new ArrayList<>();
        Map<String, String> parents = parents(step, binding.parent(), graph);
        for (Map.Entry<String, String> e : (parents != null ? parents : treeParent).entrySet()) {
            treeEdges.add(new GraphEdge(e.getValue(), e.getKey(), null));
        }

        List<String> onStack = new ArrayList<>();
        if (spec.recursive()) {
            for (int f = step.stack().size() - 1; f >= 0; f--) {
                FrameSnapshot frame = step.stack().get(f);
                if (frame.methodName().equals(spec.method()) && spec.methodContains(frame.line())) {
                    String v = label(lookup(step, f, spec.vertex()));
                    if (v != null) {
                        onStack.add(v);
                    }
                }
            }
        }

        List<VertexValue> distances = new ArrayList<>();
        dist.forEach((v, d) -> distances.add(new VertexValue(v, d)));
        return new GraphInsight(spec.graph().text(), binding.graph(), graph.representation(), directed, weighted,
                graph.vertices(), edges(graph, directed), current, examining, visited, frontier,
                binding.frontier() != null ? spec.frontier() : null,
                binding.frontier() != null ? spec.frontierOrder() : null,
                onStack, List.copyOf(order), treeEdges, distances,
                binding.dist() != null ? spec.dist() : null, graph.truncated());
    }

    // ---------- which spec and objects --------------------------------------------------

    private Binding bind(TraceStep step) {
        for (int f = 0; f < step.stack().size(); f++) {
            FrameSnapshot frame = step.stack().get(f);
            GraphSpec spec = specFor(frame);
            if (spec != null) {
                EvalContext ctx = new EvalContext(step, f);
                Binding b = new Binding(spec, f, ref(ExpressionEvaluator.evaluate(spec.graph(), ctx)),
                        ref(ctx, spec.visited()), ref(ctx, spec.dist()), ref(ctx, spec.parent()),
                        ref(ctx, spec.frontier()));
                if (b.graph() != null) {
                    last = b;
                    return b;
                }
            }
        }
        if (last == null) {
            last = firstBinding();
        }
        return last == null ? null
                : new Binding(last.spec(), -1, last.graph(), last.visited(), last.dist(), last.parent(),
                last.frontier());
    }

    /** The innermost spec in this frame's method, preferring the one whose loop holds the current line. */
    private GraphSpec specFor(FrameSnapshot frame) {
        GraphSpec found = null;
        for (GraphSpec s : specs) {
            if (s.method().equals(frame.methodName()) && s.methodContains(frame.line())) {
                if (frame.line() >= s.loopLine() && frame.line() <= s.bodyEnd()) {
                    return s;
                }
                if (found == null) {
                    found = s;
                }
            }
        }
        return found;
    }

    /** Before the traversal runs (main building the graph), use the objects it will use. */
    private Binding firstBinding() {
        for (TraceStep step : steps) {
            for (int f = 0; f < step.stack().size(); f++) {
                GraphSpec spec = specFor(step.stack().get(f));
                if (spec != null) {
                    EvalContext ctx = new EvalContext(step, f);
                    Long graph = ref(ExpressionEvaluator.evaluate(spec.graph(), ctx));
                    if (graph != null) {
                        return new Binding(spec, -1, graph, null, null, null, null);
                    }
                }
            }
        }
        return null;
    }

    /** Directed or not, weighted or not: decided once per graph, from its fullest version. */
    private void decideShapes() {
        if (specs.isEmpty()) {
            return;
        }
        Map<Long, Graph> fullest = new HashMap<>();
        Map<Long, GraphSpec> specOf = new HashMap<>();
        for (TraceStep step : steps) {
            for (int f = 0; f < step.stack().size(); f++) {
                GraphSpec spec = specFor(step.stack().get(f));
                if (spec == null) {
                    continue;
                }
                Long id = ref(ExpressionEvaluator.evaluate(spec.graph(), new EvalContext(step, f)));
                HeapObjectSnapshot o = id == null ? null : step.heap().get(Long.toString(id));
                Graph g = o == null ? null : read(o, spec, step.heap());
                if (g != null && (!fullest.containsKey(id) || g.edgeCount() > fullest.get(id).edgeCount())) {
                    fullest.put(id, g);
                    specOf.put(id, spec);
                }
                break;
            }
        }
        // The graph is fullest at the end of main, often after the traversal: look there too.
        for (TraceStep step : steps) {
            for (Map.Entry<Long, GraphSpec> e : specOf.entrySet()) {
                HeapObjectSnapshot o = step.heap().get(Long.toString(e.getKey()));
                Graph g = o == null ? null : read(o, e.getValue(), step.heap());
                if (g != null && g.edgeCount() > fullest.get(e.getKey()).edgeCount()) {
                    fullest.put(e.getKey(), g);
                }
            }
        }
        for (Map.Entry<Long, Graph> e : fullest.entrySet()) {
            Graph g = e.getValue();
            boolean symmetric = true;
            boolean weighted = false;
            for (Map.Entry<String, List<GraphEdge>> out : g.out().entrySet()) {
                for (GraphEdge edge : out.getValue()) {
                    if (edge.weight() != null && !edge.weight().equals("1")) {
                        weighted = true;
                    }
                    boolean back = g.out().getOrDefault(edge.to(), List.of()).stream()
                            .anyMatch(r -> r.to().equals(edge.from()) && Objects.equals(r.weight(), edge.weight()));
                    if (!back) {
                        symmetric = false;
                    }
                }
            }
            directedByGraph.put(e.getKey(), !symmetric || g.edgeCount() == 0);
            weightedByGraph.put(e.getKey(), weighted || specOf.get(e.getKey()).weight() != null
                    && !specOf.get(e.getKey()).shape().equals("matrix"));
        }
    }

    // ---------- reading the graph -------------------------------------------------------

    private Graph read(HeapObjectSnapshot g, GraphSpec spec, Map<String, HeapObjectSnapshot> heap) {
        Map<String, List<GraphEdge>> out = new LinkedHashMap<>();
        Set<String> vertices = new LinkedHashSet<>();
        boolean truncated = g.truncated();

        if (spec.shape().equals("matrix")) {
            if (!"array".equals(g.kind())) {
                return null;
            }
            int n = g.elements().size();
            for (int i = 0; i < n; i++) {
                vertices.add(Integer.toString(i));
            }
            for (int i = 0; i < n; i++) {
                HeapObjectSnapshot row = object(heap, g.elements().get(i));
                if (row == null) {
                    truncated = true;
                    continue;
                }
                if (!"array".equals(row.kind()) || row.length() != g.length()) {
                    return null; // not a square matrix: not an adjacency matrix
                }
                List<GraphEdge> edges = new ArrayList<>();
                for (int j = 0; j < row.elements().size(); j++) {
                    ValueSnapshot cell = row.elements().get(j);
                    if (isEdgeCell(cell)) {
                        edges.add(new GraphEdge(Integer.toString(i), Integer.toString(j),
                                cell.value() instanceof Boolean ? null : cell.display()));
                    }
                }
                out.put(Integer.toString(i), edges);
            }
            return new Graph("adjacency-matrix", List.copyOf(vertices), out, truncated);
        }

        Map<String, ValueSnapshot> lists = new LinkedHashMap<>();
        String representation = "adjacency-list";
        if (g.entries() != null) {
            representation = "adjacency-map";
            g.entries().forEach(e -> lists.put(label(e.key()), e.value()));
        } else if ("array".equals(g.kind()) || "collection".equals(g.kind())) {
            for (int i = 0; i < g.elements().size(); i++) {
                lists.put(Integer.toString(i), g.elements().get(i));
            }
        } else {
            return null;
        }
        for (Map.Entry<String, ValueSnapshot> e : lists.entrySet()) {
            String u = e.getKey();
            vertices.add(u);
            List<GraphEdge> edges = new ArrayList<>();
            HeapObjectSnapshot neighbours = object(heap, e.getValue());
            if (neighbours == null) {
                truncated |= e.getValue().ref() != null;
                out.put(u, edges);
                continue;
            }
            if (!("array".equals(neighbours.kind()) || "collection".equals(neighbours.kind()))) {
                return null;
            }
            truncated |= neighbours.truncated();
            for (ValueSnapshot element : neighbours.elements()) {
                String to = label(part(heap, element, spec.target().isEmpty() ? "" : spec.target(), true));
                if (to == null) {
                    truncated |= element.ref() != null && object(heap, element) == null;
                    continue;
                }
                String weight = spec.weight() == null || spec.weight().equals("cell") ? null
                        : display(part(heap, element, spec.weight(), false));
                edges.add(new GraphEdge(u, to, weight));
            }
            out.put(u, edges);
        }
        for (List<GraphEdge> edges : out.values()) {
            for (GraphEdge edge : edges) {
                vertices.add(edge.to());
            }
        }
        return new Graph(representation, List.copyOf(vertices), out, truncated);
    }

    private static boolean isEdgeCell(ValueSnapshot cell) {
        if (cell.value() instanceof Boolean b) {
            return b;
        }
        if (cell.value() instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && Math.abs(d) < INFINITY_THRESHOLD;
        }
        return false;
    }

    /** A part of an element: "" = the element itself, "[k]" = array cell, ".f" = field. */
    private static ValueSnapshot part(Map<String, HeapObjectSnapshot> heap, ValueSnapshot element, String accessor,
                                      boolean vertex) {
        if (accessor == null || accessor.isEmpty()) {
            return element.ref() == null ? element : null;
        }
        HeapObjectSnapshot o = object(heap, element);
        if (o == null) {
            return null;
        }
        if (accessor.startsWith("[")) {
            int k = Integer.parseInt(accessor.substring(1, accessor.length() - 1));
            return "array".equals(o.kind()) && k < o.elements().size() ? o.elements().get(k) : null;
        }
        String field = accessor.substring(1);
        for (VariableSnapshot f : o.fields()) {
            if (f.name().equals(field)) {
                return f.value();
            }
        }
        return null;
    }

    private GraphEdge neighbourEdge(TraceStep step, GraphSpec spec, String current) {
        ValueSnapshot element = lookup(step, 0, spec.neighbor());
        if (element == null) {
            return null;
        }
        if (spec.shape().equals("matrix")) {
            String to = label(element);
            return to == null ? null : new GraphEdge(current, to, null);
        }
        String to = label(part(step.heap(), element, spec.target(), true));
        if (to == null) {
            return null;
        }
        String weight = spec.weight() == null ? null : display(part(step.heap(), element, spec.weight(), false));
        return new GraphEdge(current, to, weight);
    }

    /** Edges to draw: for an undirected graph each pair once (u–v and v–u are one edge). */
    private static List<GraphEdge> edges(Graph graph, boolean directed) {
        List<GraphEdge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<GraphEdge> out : graph.out().values()) {
            for (GraphEdge e : out) {
                String key = directed ? e.from() + "→" + e.to()
                        : e.from().compareTo(e.to()) < 0 ? e.from() + "—" + e.to() : e.to() + "—" + e.from();
                if (seen.add(key)) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    // ---------- traversal state ---------------------------------------------------------

    private static List<String> visited(TraceStep step, Long ref, Graph graph) {
        HeapObjectSnapshot o = ref == null ? null : step.heap().get(Long.toString(ref));
        List<String> result = new ArrayList<>();
        if (o == null) {
            return result;
        }
        if ("array".equals(o.kind())) {
            for (int i = 0; i < o.elements().size(); i++) {
                Object value = o.elements().get(i).value();
                boolean marked = value instanceof Boolean b ? b : value instanceof Number n && n.longValue() != 0;
                if (marked) {
                    result.add(Integer.toString(i));
                }
            }
        } else if ("collection".equals(o.kind()) && o.entries() == null) {
            for (ValueSnapshot e : o.elements()) {
                String v = label(e);
                if (v != null) {
                    result.add(v);
                }
            }
        }
        return sortByVertexOrder(result, graph);
    }

    private static Map<String, String> distances(TraceStep step, Long ref, Graph graph) {
        HeapObjectSnapshot o = ref == null ? null : step.heap().get(Long.toString(ref));
        Map<String, String> result = new LinkedHashMap<>();
        if (o == null) {
            return result;
        }
        if ("array".equals(o.kind())) {
            for (int i = 0; i < o.elements().size(); i++) {
                result.put(Integer.toString(i), distance(o.elements().get(i)));
            }
        } else if (o.entries() != null) {
            for (String v : graph.vertices()) {
                o.entries().stream().filter(e -> v.equals(label(e.key()))).findFirst()
                        .ifPresent(e -> result.put(v, distance(e.value())));
            }
        }
        return result;
    }

    private static String distance(ValueSnapshot v) {
        if (v.value() instanceof Number n) {
            return Math.abs(n.doubleValue()) >= INFINITY_THRESHOLD ? "∞" : v.display();
        }
        if ("Infinity".equals(v.display())) {
            return "∞";
        }
        return v.display();
    }

    private static Map<String, String> parents(TraceStep step, Long ref, Graph graph) {
        HeapObjectSnapshot o = ref == null ? null : step.heap().get(Long.toString(ref));
        if (o == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        if ("array".equals(o.kind())) {
            for (int i = 0; i < o.elements().size(); i++) {
                String p = label(o.elements().get(i));
                String child = Integer.toString(i);
                if (p != null && !p.equals(child) && graph.vertices().contains(p)) {
                    result.put(child, p);
                }
            }
        } else if (o.entries() != null) {
            o.entries().forEach(e -> {
                String child = label(e.key());
                String p = label(e.value());
                if (child != null && p != null && !p.equals(child)) {
                    result.put(child, p);
                }
            });
        }
        return result;
    }

    private static List<String> frontier(TraceStep step, Long ref, GraphSpec spec) {
        HeapObjectSnapshot o = ref == null ? null : step.heap().get(Long.toString(ref));
        List<String> result = new ArrayList<>();
        if (o == null || !"collection".equals(o.kind())) {
            return result;
        }
        String accessor = spec.frontierVertex() == null ? "" : spec.frontierVertex();
        boolean stackTopFirst = !"last".equals(o.top());
        for (ValueSnapshot e : o.elements()) {
            String v = label(part(step.heap(), e, accessor, true));
            if (v != null) {
                result.add(v);
            }
        }
        if ("stack".equals(spec.frontierOrder()) && !stackTopFirst) {
            java.util.Collections.reverse(result); // always list a stack from its top
        }
        return result;
    }

    /** A vertex that just entered the frontier was put there by the vertex being processed. */
    private void updatePushers(List<String> frontier, boolean inBodyNow) {
        if (previousCurrent == null || !(previousInBody || inBodyNow)) {
            return;
        }
        Map<String, Integer> before = counts(previousFrontier);
        Map<String, Integer> now = counts(frontier);
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            if (e.getValue() > before.getOrDefault(e.getKey(), 0)) {
                pusher.put(e.getKey(), previousCurrent);
            }
        }
    }

    /**
     * A distance set while processing u's neighbours makes u the parent of that vertex in the
     * BFS / shortest-path tree; a decrease is a relaxation.
     */
    private void updateFromDistances(Map<String, String> dist) {
        if (!previousInBody || previousCurrent == null || previousDist.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : dist.entrySet()) {
            String before = previousDist.get(e.getKey());
            if (before != null && !before.equals(e.getValue())) {
                treeParent.put(e.getKey(), previousCurrent);
                if (numeric(e.getValue()) < numeric(before)) {
                    newRelaxations++;
                }
            }
        }
    }

    private static double numeric(String display) {
        if (display.equals("∞")) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            return Double.parseDouble(display);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The innermost running call of the traversal whose vertex is not v: it discovered v. */
    private String discoverer(TraceStep step, GraphSpec spec, String v) {
        for (int f = 0; f < step.stack().size(); f++) {
            FrameSnapshot frame = step.stack().get(f);
            if (!frame.methodName().equals(spec.method()) || !spec.methodContains(frame.line())) {
                continue;
            }
            String u = label(lookup(step, f, spec.vertex()));
            if (u != null && !u.equals(v)) {
                return u;
            }
        }
        return null;
    }

    // ---------- helpers -----------------------------------------------------------------

    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> result = new HashMap<>();
        values.forEach(v -> result.merge(v, 1, Integer::sum));
        return result;
    }

    private static List<String> sortByVertexOrder(List<String> labels, Graph graph) {
        List<String> sorted = new ArrayList<>(labels);
        sorted.sort((a, b) -> Integer.compare(index(graph, a), index(graph, b)));
        return sorted;
    }

    private static int index(Graph graph, String v) {
        int i = graph.vertices().indexOf(v);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static ValueSnapshot lookup(TraceStep step, int frame, String name) {
        if (name == null) {
            return null;
        }
        EvalContext ctx = new EvalContext(step, frame);
        return ctx.lookup(name.startsWith("this.") ? name.substring(5) : name);
    }

    private static Long ref(EvalContext ctx, String name) {
        if (name == null) {
            return null;
        }
        ValueSnapshot v = ctx.lookup(name.startsWith("this.") ? name.substring(5) : name);
        return v == null ? null : v.ref();
    }

    private static Long ref(Object evaluated) {
        return evaluated instanceof HeapRef r ? r.id() : null;
    }

    private static HeapObjectSnapshot object(Map<String, HeapObjectSnapshot> heap, ValueSnapshot v) {
        return v == null || v.ref() == null ? null : heap.get(Long.toString(v.ref()));
    }

    /** A vertex label: the value as written without quotes (3, A); null for references and null. */
    static String label(ValueSnapshot v) {
        if (v == null || v.ref() != null || "null".equals(v.kind())) {
            return null;
        }
        Object value = v.value();
        if (value instanceof Number n && !(value instanceof Double)) {
            return Long.toString(n.longValue());
        }
        return value != null ? value.toString() : v.display();
    }

    private static String display(ValueSnapshot v) {
        return v == null || v.ref() != null ? null : v.display();
    }
}
