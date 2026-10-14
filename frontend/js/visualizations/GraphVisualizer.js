// Draws the graph a traversal works on, from step.insight.graph (built by the backend from the heap):
// vertices coloured by their state (current, waiting in the frontier, visited), the edge being examined,
// the BFS/DFS/shortest-path tree found so far, edge weights and the distance of every vertex.
//
// The layout is computed once per trace from the most complete version of the graph, so vertices stay
// put while the program adds edges or the traversal runs. It is a deterministic force-directed layout:
// the same graph always gets the same picture.

import { arrowMarker, el, svgRoot, text } from "./svg.js";

const R = 17;
const WIDTH = 420;
const HEIGHT = 250;
const PAD = 34;

const ORDER_WORDS = {
    queue: "front → rear",
    stack: "top first",
    priority: "priority queue, heap order",
};

export class GraphVisualizer {
    constructor(container) {
        this.container = container;
        this.layouts = new Map(); // graph ref → Map<vertex, {x, y}>
    }

    /** Lays out every graph of the trace once, using the union of its vertices and edges over all steps. */
    load(steps) {
        this.layouts.clear();
        const unions = new Map();
        for (const step of steps ?? []) {
            const g = step.insight?.graph;
            if (!g) continue;
            const u = unions.get(g.ref) ?? { vertices: [], edges: new Map() };
            for (const v of g.vertices) if (!u.vertices.includes(v)) u.vertices.push(v);
            for (const e of g.edges) {
                const key = [e.from, e.to].sort().join("|");
                u.edges.set(key, [e.from, e.to]);
            }
            unions.set(g.ref, u);
        }
        for (const [ref, u] of unions) {
            this.layouts.set(ref, layout(u.vertices, [...u.edges.values()]));
        }
    }

    /** Draws the graph of this step; returns 1 if one was drawn, else 0. */
    /** @param paint optional colour number per vertex index (graph colouring: color[v] = c, 0 = none) */
    render(step, previous, paint = null) {
        this.container.replaceChildren();
        const g = step.insight?.graph;
        if (!g || g.vertices.length === 0) return 0;
        let positions = this.layouts.get(g.ref);
        if (!positions || g.vertices.some((v) => !positions.has(v))) {
            positions = layout(g.vertices, g.edges.map((e) => [e.from, e.to]));
            this.layouts.set(g.ref, positions);
        }
        const before = previous?.insight?.graph?.ref === g.ref ? previous.insight.graph : null;
        this.container.append(drawGraph(g, positions, before, paint));
        return 1;
    }
}

function drawGraph(g, positions, before, paint) {
    const block = document.createElement("div");
    block.className = "array-block graph-block";

    const title = document.createElement("div");
    title.className = "array-title";
    title.textContent = g.name;
    const meta = document.createElement("span");
    meta.className = "meta";
    const kind = `${g.directed ? "directed" : "undirected"}${g.weighted ? ", weighted" : ""}`;
    meta.textContent = `${g.representation.replace("-", " ")} · ${kind} · V = ${g.vertices.length}, E = ${g.edges.length}`;
    title.append(meta);
    block.append(title);

    // Visited = marked in the visited array/set, or already processed (Dijkstra with a heap keeps no
    // visited array; and once the traversal returns, its visited array is out of scope).
    const visited = new Set([...g.visited, ...g.order]);
    const frontier = new Set(g.frontier);
    const onStack = new Set(g.onStack);
    const order = new Map(g.order.map((v, i) => [v, i + 1]));
    const tree = new Set(g.treeEdges.map((e) => key(e.from, e.to, true)));
    const dist = new Map(g.distances.map((d) => [d.vertex, d.value]));
    const oldDist = new Map((before?.distances ?? []).map((d) => [d.vertex, d.value]));
    const examined = g.examining;
    const examinedIsEdge = examined
        && g.edges.some((e) => key(e.from, e.to, g.directed) === key(examined.from, examined.to, g.directed));

    const svg = svgRoot(WIDTH, HEIGHT, describe(g));
    svg.classList.add("graph-svg");
    const arrow = g.directed ? arrowMarker(svg, "graph-arrowhead") : null;
    const hotArrow = g.directed ? arrowMarker(svg, "graph-arrowhead hot") : null;
    const at = (v) => positions.get(v);

    // Edges first, so vertices sit on top of them.
    for (const e of g.edges) {
        const a = at(e.from);
        const b = at(e.to);
        if (!a || !b) continue;
        const isExamined = examined && key(e.from, e.to, g.directed) === key(examined.from, examined.to, g.directed);
        const isTree = tree.has(key(e.from, e.to, true)) || (!g.directed && tree.has(key(e.to, e.from, true)));
        const isNew = before && !before.edges.some((o) => key(o.from, o.to, g.directed) === key(e.from, e.to, g.directed));
        const cls = ["graph-edge", isTree ? "tree" : "", isExamined ? "hot" : "", isNew ? "new" : ""].filter(Boolean).join(" ");
        const [x1, y1, x2, y2] = shorten(a, b, R, g.directed ? R + 3 : R);
        svg.append(el("line", { class: cls, x1, y1, x2, y2, "marker-end": g.directed ? (isExamined ? hotArrow : arrow) : null }));
    }
    // A matrix cell that is being checked but holds no edge: show the pair being tested.
    if (examined && !examinedIsEdge && at(examined.from) && at(examined.to) && examined.from !== examined.to) {
        const [x1, y1, x2, y2] = shorten(at(examined.from), at(examined.to), R, R);
        svg.append(el("line", { class: "graph-edge probe", x1, y1, x2, y2 }));
    }
    if (g.weighted) {
        for (const e of g.edges) {
            const a = at(e.from);
            const b = at(e.to);
            if (!a || !b || e.weight == null) continue;
            const isExamined = examined && key(e.from, e.to, g.directed) === key(examined.from, examined.to, g.directed);
            const mx = (a.x + b.x) / 2;
            const my = (a.y + b.y) / 2;
            const w = 8 + 7 * e.weight.length;
            const label = el("g", { class: `edge-weight${isExamined ? " hot" : ""}` });
            label.append(el("rect", { x: mx - w / 2, y: my - 9, width: w, height: 18, rx: 4 }));
            label.append(text(null, mx, my, e.weight, "middle"));
            svg.append(label);
        }
    }

    for (const v of g.vertices) {
        const p = at(v);
        if (!p) continue;
        const isTarget = examined && examined.to === v && examined.from !== v;
        const cls = ["graph-vertex",
            visited.has(v) ? "visited" : "",
            frontier.has(v) && g.current !== v ? "frontier" : "",
            onStack.has(v) && g.current !== v ? "on-stack" : "",
            g.current === v ? "current" : "",
            isTarget ? "target" : ""].filter(Boolean).join(" ");
        const colour = paint && /^\d+$/.test(v) ? paint[Number(v)] ?? 0 : 0;
        const node = el("g", { class: colour > 0 ? `${cls} paint paint-${((colour - 1) % 6) + 1}` : cls });
        node.append(el("circle", { cx: p.x, cy: p.y, r: R }));
        if (colour > 0) node.append(el("title", {}, `vertex ${v}: colour ${colour}`));
        node.append(text("vertex-label", p.x, p.y, v.length > 4 ? `${v.slice(0, 3)}…` : v, "middle"));
        svg.append(node);

        if (order.has(v)) {
            svg.append(text("visit-order", p.x + R - 1, p.y - R + 1, `#${order.get(v)}`, "start"));
        }
        if (dist.has(v)) {
            const changed = before && oldDist.has(v) && oldDist.get(v) !== dist.get(v);
            const d = text(`vertex-dist${changed ? " changed" : ""}`, p.x, p.y + R + 12, `d=${dist.get(v)}`, "middle");
            if (changed) d.append(el("title", {}, `${g.distName}[${v}] changed from ${oldDist.get(v)} to ${dist.get(v)}`));
            svg.append(d);
        }
    }

    const scroll = document.createElement("div");
    scroll.className = "array-scroll";
    scroll.append(svg);
    block.append(scroll);
    block.append(stateLines(g, examined, examinedIsEdge, dist, oldDist, before));
    block.append(legend(g));
    return block;
}

/** Text under the picture: what is happening, the frontier, the visit order, the distances. */
function stateLines(g, examined, examinedIsEdge, dist, oldDist, before) {
    const box = document.createElement("div");
    box.className = "graph-state";
    if (g.current != null) {
        let what = `Processing vertex ${g.current}`;
        if (examined) {
            const w = examined.weight != null ? ` (weight ${examined.weight})` : "";
            what += examinedIsEdge || g.representation !== "adjacency-matrix"
                ? `, looking at edge ${examined.from} → ${examined.to}${w}`
                : `, checking ${g.name}[${examined.from}][${examined.to}]: no edge`;
        }
        box.append(line("now", what));
    }
    if (g.frontierName) {
        const order = ORDER_WORDS[g.frontierOrder] ?? g.frontierOrder;
        box.append(line(`${g.frontierName} (${order})`, g.frontier.length ? g.frontier.join(", ") : "empty"));
    }
    if (g.onStack.length) {
        box.append(line("recursion path", g.onStack.join(" → ")));
    }
    if (g.order.length) {
        box.append(line("visit order", g.order.join(" → ")));
    }
    if (dist.size) {
        const row = document.createElement("div");
        row.className = "graph-line";
        const label = document.createElement("span");
        label.className = "graph-line-label";
        label.textContent = g.distName ?? "dist";
        const table = document.createElement("span");
        table.className = "dist-table";
        for (const [v, d] of dist) {
            const cell = document.createElement("span");
            const changed = before && oldDist.has(v) && oldDist.get(v) !== d;
            cell.className = `dist-cell${changed ? " changed" : ""}`;
            const head = document.createElement("b");
            head.textContent = v;
            cell.append(head, ` ${d}`);
            if (changed) cell.title = `was ${oldDist.get(v)}`;
            table.append(cell);
        }
        row.append(label, table);
        box.append(row);
    }
    return box;
}

function legend(g) {
    const p = document.createElement("div");
    p.className = "legend graph-legend";
    const items = [["current", "Current vertex"]];
    if (g.frontierName) items.push(["frontier", `In ${g.frontierName}`]);
    if (g.onStack.length || g.frontierOrder === "stack") items.push(["on-stack", "On the recursion path"]);
    items.push(["visited", g.distName && !g.visited.length ? "Processed" : "Visited"]);
    items.push(["tree", g.distName ? "Tree edge (parent → child)" : "Tree edge"]);
    items.push(["hot", "Edge being examined"]);
    for (const [cls, label] of items) {
        if (cls === "on-stack" && !g.onStack.length) continue;
        const span = document.createElement("span");
        const swatch = document.createElement("i");
        swatch.className = `swatch graph-${cls}`;
        span.append(swatch, label);
        p.append(span);
    }
    return p;
}

function line(label, value) {
    const row = document.createElement("div");
    row.className = "graph-line";
    const l = document.createElement("span");
    l.className = "graph-line-label";
    l.textContent = label;
    const v = document.createElement("code");
    v.textContent = value;
    row.append(l, v);
    return row;
}

function describe(g) {
    const edges = g.edges.map((e) => `${e.from}${g.directed ? "→" : "–"}${e.to}${e.weight != null ? ` (${e.weight})` : ""}`);
    return `Graph ${g.name}: vertices ${g.vertices.join(", ")}; edges ${edges.join(", ")}`;
}

function key(a, b, directed) {
    return directed || a < b ? `${a}|${b}` : `${b}|${a}`;
}

/** The segment between two circles, trimmed so it starts and ends at their borders. */
function shorten(a, b, ra, rb) {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const len = Math.hypot(dx, dy) || 1;
    return [a.x + (dx / len) * ra, a.y + (dy / len) * ra, b.x - (dx / len) * rb, b.y - (dy / len) * rb];
}

/**
 * Deterministic force-directed layout (Fruchterman-Reingold), started from a circle in vertex order.
 * Edges pull their ends together, all vertices push each other apart; then the result is scaled
 * to fill the drawing.
 */
export function layout(vertices, edges) {
    const n = vertices.length;
    const pos = new Map();
    if (n === 0) return pos;
    if (n === 1) {
        pos.set(vertices[0], { x: WIDTH / 2, y: HEIGHT / 2 });
        return pos;
    }
    const index = new Map(vertices.map((v, i) => [v, i]));
    const xs = vertices.map((_, i) => Math.cos((2 * Math.PI * i) / n - Math.PI / 2));
    const ys = vertices.map((_, i) => Math.sin((2 * Math.PI * i) / n - Math.PI / 2));
    const links = edges.map(([a, b]) => [index.get(a), index.get(b)]).filter(([a, b]) => a !== undefined && b !== undefined && a !== b);
    const k = Math.sqrt(4 / n);
    let temperature = 0.2;
    for (let iter = 0; iter < 300; iter++) {
        const fx = new Array(n).fill(0);
        const fy = new Array(n).fill(0);
        for (let i = 0; i < n; i++) {
            for (let j = i + 1; j < n; j++) {
                let dx = xs[i] - xs[j];
                let dy = ys[i] - ys[j];
                let d = Math.hypot(dx, dy);
                if (d < 1e-6) { dx = 0.01 * (i - j); dy = 0.01; d = Math.hypot(dx, dy); }
                const f = (k * k) / d;
                fx[i] += (dx / d) * f; fy[i] += (dy / d) * f;
                fx[j] -= (dx / d) * f; fy[j] -= (dy / d) * f;
            }
        }
        for (const [a, b] of links) {
            const dx = xs[a] - xs[b];
            const dy = ys[a] - ys[b];
            const d = Math.hypot(dx, dy) || 1e-6;
            const f = (d * d) / k;
            fx[a] -= (dx / d) * f; fy[a] -= (dy / d) * f;
            fx[b] += (dx / d) * f; fy[b] += (dy / d) * f;
        }
        for (let i = 0; i < n; i++) {
            const d = Math.hypot(fx[i], fy[i]) || 1e-6;
            const step = Math.min(d, temperature);
            xs[i] += (fx[i] / d) * step;
            ys[i] += (fy[i] / d) * step;
        }
        temperature *= 0.985;
    }
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    const sx = (WIDTH - 2 * PAD) / Math.max(maxX - minX, 1e-6);
    const sy = (HEIGHT - 2 * PAD - 12) / Math.max(maxY - minY, 1e-6);
    const s = Math.min(sx, sy);
    const offX = (WIDTH - s * (maxX - minX)) / 2;
    const offY = (HEIGHT - 12 - s * (maxY - minY)) / 2;
    vertices.forEach((v, i) => pos.set(v, { x: offX + (xs[i] - minX) * s, y: offY + (ys[i] - minY) * s }));
    return pos;
}
