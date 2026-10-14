package com.vizard.execution.algorithms;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.vizard.execution.insight.CodeModel.GraphSpec;
import com.vizard.execution.insight.GraphModelBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recognisers for graph algorithms. All start from the neighbour loop {@link GraphModelBuilder}
 * finds, then look at what the traversal does around it:
 * <ul>
 *   <li><b>BFS</b>: vertices wait in a FIFO queue, and each is marked (visited, or given a level
 *       {@code dist[v] = dist[u] + 1}) so it enters the queue only once.</li>
 *   <li><b>DFS</b>: the method calls itself on unvisited neighbours (recursive), or vertices wait
 *       on a LIFO stack (iterative).</li>
 *   <li><b>Dijkstra</b>: an edge relaxation {@code if (dist[u] + w < dist[v]) dist[v] = dist[u] + w},
 *       with the next vertex taken from a priority queue (binary heap) or found by scanning the
 *       distances for the smallest one (array version).</li>
 * </ul>
 * Look-alikes that share the neighbour loop but are none of these (Prim's MST updates
 * {@code key[v] = w} without adding; Kahn's topological sort uses in-degrees, not visited marks;
 * Bellman-Ford/SPFA relax from a plain queue) are deliberately not recognised.
 */
final class GraphAlgorithms {

    static final String MATRIX = "matrix";
    static final String HEAP = "heap";
    static final String SKIPS_STALE = "skips-stale";
    static final String ITERATIVE = "iterative";

    private GraphAlgorithms() {
    }

    static Optional<Detection> detect(MethodDeclaration m, CompilationUnit cu) {
        for (GraphSpec spec : GraphModelBuilder.find(m, cu)) {
            Optional<Detection> d = dijkstra(m, spec).or(() -> bfs(m, spec)).or(() -> dfs(m, spec));
            if (d.isPresent()) {
                return d;
            }
        }
        return Optional.empty();
    }

    private static Optional<Detection> dijkstra(MethodDeclaration m, GraphSpec s) {
        boolean heap = "priority".equals(s.frontierOrder());
        if (!s.relaxes() || !(heap || s.minScan())) {
            return Optional.empty();
        }
        Set<String> traits = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        evidence.add(loopEvidence(s));
        evidence.add("Relaxes edges: when " + s.dist() + "[u] + w is shorter than " + s.dist() + "[v], it becomes the "
                + "new " + s.dist() + "[v] (inside the loop at line " + s.loopLine() + ").");
        if (heap) {
            traits.add(HEAP);
            evidence.add("Takes the closest unfinished vertex from the priority queue " + s.frontier()
                    + " (a binary min-heap: O(log n) per offer and poll).");
            if (skipsStale(m, s)) {
                traits.add(SKIPS_STALE);
            }
        } else {
            evidence.add("Finds the closest unfinished vertex by scanning all distances (no heap).");
        }
        if (s.shape().equals(MATRIX)) {
            traits.add(MATRIX);
        }
        return Optional.of(detection(Algorithm.DIJKSTRA, m, evidence, traits));
    }

    private static Optional<Detection> bfs(MethodDeclaration m, GraphSpec s) {
        if (!"queue".equals(s.frontierOrder()) || s.relaxes() || (s.visited() == null && !s.levels())) {
            return Optional.empty();
        }
        Set<String> traits = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        evidence.add(loopEvidence(s));
        evidence.add("Vertices wait in the FIFO queue " + s.frontier() + ": first discovered, first processed, "
                + "so they come out in order of distance from the start.");
        evidence.add(s.visited() != null
                ? "Each neighbour is marked in " + s.visited() + " before it is queued, so no vertex is queued twice."
                : "Each neighbour gets its level " + s.dist() + "[v] = " + s.dist() + "[u] + 1 when first reached.");
        if (s.shape().equals(MATRIX)) {
            traits.add(MATRIX);
        }
        return Optional.of(detection(Algorithm.BFS, m, evidence, traits));
    }

    private static Optional<Detection> dfs(MethodDeclaration m, GraphSpec s) {
        boolean recursive = s.recursive();
        boolean stack = "stack".equals(s.frontierOrder());
        if (s.visited() == null || s.relaxes() || !(recursive || stack)) {
            return Optional.empty();
        }
        Set<String> traits = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        evidence.add(loopEvidence(s));
        if (recursive) {
            traits.add(Detection.RECURSIVE);
            evidence.add(m.getNameAsString() + "() calls itself on each unvisited neighbour, so it goes as deep as "
                    + "possible before backing up (the call stack remembers the way back).");
        } else {
            traits.add(ITERATIVE);
            evidence.add("Vertices wait on the stack " + s.frontier() + ": the last one pushed is explored first.");
        }
        evidence.add("Vertices are marked in " + s.visited() + " so each is explored once.");
        if (s.shape().equals(MATRIX)) {
            traits.add(MATRIX);
        }
        return Optional.of(detection(Algorithm.DFS, m, evidence, traits));
    }

    private static String loopEvidence(GraphSpec s) {
        return s.shape().equals(MATRIX)
                ? "Scans row " + s.vertex() + " of the adjacency matrix " + s.graph().text() + " (line " + s.loopLine()
                + "): " + s.graph().text() + "[" + s.vertex() + "][" + s.neighbor() + "] says whether the edge exists."
                : "Loops over the neighbours of " + s.vertex() + " in the adjacency list " + s.graph().text()
                + " (line " + s.loopLine() + ").";
    }

    /** {@code if (d > dist[u]) continue;} or {@code if (visited[u]) continue;}: outdated heap entries are skipped. */
    private static boolean skipsStale(MethodDeclaration m, GraphSpec s) {
        for (IfStmt guard : m.findAll(IfStmt.class)) {
            if (s.loopLine() <= line(guard) && line(guard) <= s.bodyEnd()) {
                continue; // inside the neighbour loop
            }
            boolean leaves = !guard.getThenStmt().findAll(ContinueStmt.class).isEmpty()
                    || guard.getThenStmt() instanceof ContinueStmt;
            String condition = guard.getCondition().toString();
            boolean aboutVertex = s.dist() != null && condition.contains(s.dist() + "[")
                    || s.visited() != null && condition.contains(s.visited());
            if (leaves && aboutVertex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Methods a recognised graph or backtracking algorithm calls (minDistance() in Dijkstra, isSafe() in
     * N-Queens) are part of it, not reported alone.
     */
    static boolean isPartOf(Detection helper, List<Detection> found, CompilationUnit cu) {
        for (Detection d : found) {
            boolean whole = d.algorithm().category().equals("Graphs") || d.algorithm().category().equals("Backtracking");
            if (d == helper || !whole) {
                continue;
            }
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                if (m.getNameAsString().equals(d.method()) && m.findAll(MethodCallExpr.class)
                        .stream().anyMatch(c -> c.getNameAsString().equals(helper.method()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Detection detection(Algorithm algorithm, MethodDeclaration m, List<String> evidence,
                                       Set<String> traits) {
        return new Detection(algorithm, m.getNameAsString(), line(m), List.copyOf(evidence), Set.copyOf(traits));
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }
}
