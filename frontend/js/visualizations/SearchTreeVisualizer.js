// Draws the state-space tree of a backtracking search as it stood at the current step.
//
// The backend sends the whole tree once (analysis.searchTrees): every node knows the step it appears
// at (firstStep), the last step its call was running (endStep) and the step from which its outcome is
// known (kindStep). So the picture for step k is a pure function of k: nodes with firstStep > k are not
// there yet; a node whose call is still running is on the current path; the deepest of those is the
// node being worked on now.
//
// Branch-and-bound trees (kind "bnb") come from a live-node list instead of recursion: a node is
// generated, waits in the list (live), is taken out (polled) and then either expanded (children made)
// or dropped because its bound cannot beat the best solution so far (pruned).
//
// Layout: a tidy tree computed once per trace: leaves side by side in the order they were created,
// each parent centred over its children, one row per level. Nodes never move while you step.

import { el, svgRoot, text } from "./svg.js";

const R = 12;
const DX = 30;          // room for a call's circle
const DX_REJECTED = 20; // a rejected choice is a small square
const DY = 50;
const TOP = 22;
const PAD = 22;

const KIND_TEXT = {
    explored: "explored", solution: "solution", "dead-end": "dead end",
    pruned: "pruned by a bound check", rejected: "rejected by the check (never called)",
    computed: "computed", base: "base case", hit: "answered from the memo (not computed again)",
    repeat: "repeated: these arguments were already computed (wasted work)",
};

/** Recursion trees of DP methods (memoised or plain) rather than backtracking state-space trees. */
const isRecursion = (tree) => tree.kind === "memo" || tree.kind === "recursion";
const isBnb = (tree) => tree.kind === "bnb";

const BNB_TEXT = {
    generated: "generated, not added to the live list yet", live: "live: waiting in the list",
    taken: "taken out of the list, being examined", expanded: "expanded: its children were generated",
    pruned: "pruned: its bound cannot beat the best so far",
};
const ORDER_TEXT = { priority: "best-first (priority queue on the bound)", queue: "FIFO (queue)", stack: "LIFO (stack)" };

export class SearchTreeVisualizer {
    constructor(container, onJump) {
        this.container = container;
        this.onJump = onJump;
        this.trees = [];
        this.layouts = [];
    }

    load(analysis) {
        this.trees = analysis?.searchTrees ?? [];
        this.layouts = this.trees.map(layout);
    }

    /** Draws the trees that have started by this step; returns how many. */
    render(step) {
        const k = step.index;
        const scrollers = [...this.container.querySelectorAll(".array-scroll")].map((s) => s.scrollLeft);
        this.container.replaceChildren();
        // With several trees (TSP with and without the bound), the one that grew most recently comes first.
        const latest = (tree) => tree.nodes.reduce((m, n) => (n.firstStep <= k ? Math.max(m, n.firstStep) : m), -1);
        const order = this.trees.map((tree, t) => t).filter((t) => this.trees[t].nodes.length && this.trees[t].nodes[0].firstStep <= k);
        order.sort((a, b) => latest(this.trees[b]) - latest(this.trees[a]));
        const keyed = order.map((t) => this.trees[t].method).join();
        if (keyed !== this.lastOrder) scrollers.length = 0; // a different tree on top: don't reuse its scroll
        this.lastOrder = keyed;
        order.forEach((t, i) => this.container.append(this.#drawTree(this.trees[t], this.layouts[t], k, scrollers[i])));
        return order.length;
    }

    #drawTree(tree, pos, k, oldScroll) {
        const bnb = isBnb(tree);
        const states = tree.nodes.map((n) => (bnb ? bnbStateAt(n, k) : stateAt(n, k)));
        const visible = (n) => n.firstStep <= k;
        let current = null;
        for (const n of tree.nodes) {
            if (bnb) {
                if (n.pollStep != null && n.pollStep <= k && (!current || n.pollStep >= current.pollStep)) current = n;
            } else if (states[n.id] === "active" && (!current || n.level >= current.level)) current = n;
        }
        const best = new Set((tree.bestUpdates ?? []).filter((u) => u.step <= k && u.node != null).map((u) => u.node));
        const onPath = new Set();
        for (let n = current; n; n = n.parent == null ? null : tree.nodes[n.parent]) onPath.add(n.id);

        const block = document.createElement("div");
        block.className = "array-block search-block";
        const title = document.createElement("div");
        title.className = "array-title";
        title.textContent = `${isRecursion(tree) ? "Recursion tree" : bnb ? "Branch-and-bound tree" : "State-space tree"} · ${tree.method}()`;
        const meta = document.createElement("span");
        meta.className = "meta";
        const level = tree.depthVariable ? `level = ${tree.depthVariable}` : "level = depth of the call";
        const branch = tree.kind === "binary" ? "branch = take / leave" : `branch = ${tree.choiceVariable}`;
        meta.textContent = bnb
            ? `each node is labelled with its bound · live nodes wait in ${tree.assignment}, ${ORDER_TEXT[tree.assignmentShape] ?? tree.assignmentShape}${tree.truncated ? " · first 1500 nodes" : ""}`
            : isRecursion(tree)
            ? `each node is a call, labelled with its arguments${tree.kind === "memo" ? ` · memo: ${tree.assignment}` : " · nothing remembered"}`
            : `${level} · ${branch}${tree.truncated ? " · first 1500 nodes" : ""}`;
        title.append(meta);
        block.append(title);

        const width = Math.max(120, pos.width);
        const height = TOP + (pos.depth + 1) * DY;
        const svg = svgRoot(width, height, describe(tree, states));
        svg.classList.add("search-svg");

        for (const n of tree.nodes) {
            if (!visible(n) || n.parent == null) continue;
            const a = pos.at[n.parent];
            const b = pos.at[n.id];
            const cls = ["search-edge", onPath.has(n.id) ? "path" : "", states[n.id] === "rejected" ? "rejected" : ""]
                .filter(Boolean).join(" ");
            svg.append(el("line", { class: cls, x1: a.x, y1: a.y + R, x2: b.x, y2: b.y - (states[n.id] === "rejected" ? 7 : R) }));
        }
        for (const n of tree.nodes) {
            if (!visible(n)) continue;
            const p = pos.at[n.id];
            const state = states[n.id];
            const cls = ["search-node", state, n === current ? "current" : "", onPath.has(n.id) && n !== current ? "path" : "",
                n.firstStep === k ? "new" : ""].filter(Boolean).join(" ");
            const g = el("g", { class: cls, tabindex: 0, role: "button" });
            const what = bnb ? (n === current && state !== "pruned" && state !== "expanded" ? BNB_TEXT.taken : BNB_TEXT[state] ?? state)
                : state === "active" ? "running" : KIND_TEXT[state] ?? state;
            g.append(el("title", {}, `${n.choice} — ${what}${best.has(n.id) ? " · new best solution" : ""} (step ${n.firstStep + 1})`));
            if (state === "rejected") {
                g.append(el("rect", { x: p.x - 7, y: p.y - 7, width: 14, height: 14, rx: 3 }));
                g.append(text("search-label", p.x, p.y, "×", "middle"));
                g.append(text("search-under", p.x, p.y + 17, n.label, "middle"));
            } else {
                g.append(el("circle", { cx: p.x, cy: p.y, r: R }));
                const label = n.parent == null && !isRecursion(tree) && !bnb ? "●" : n.label;
                g.append(text("search-label", p.x, p.y, label.length > 4 ? `${label.slice(0, 3)}…` : label, "middle"));
                if (state === "solution" || best.has(n.id)) g.append(text("search-star", p.x + R - 2, p.y - R + 1, "★", "middle"));
            }
            g.addEventListener("click", () => this.onJump?.(n.firstStep));
            g.addEventListener("keydown", (e) => { if (e.key === "Enter") this.onJump?.(n.firstStep); });
            svg.append(g);
        }

        const scroll = document.createElement("div");
        scroll.className = "array-scroll search-scroll";
        scroll.append(svg);
        block.append(scroll);
        block.append(bnb ? bnbCounts(tree, states, current, k) : counts(tree, states, current, k));
        block.append(legend(tree));

        // Keep the node being worked on (or, once the search is over, the last one decided) in view,
        // without jumping around while it is visible.
        let focus = current;
        if (!focus) {
            for (const n of tree.nodes) if (n.kindStep <= k && (!focus || n.kindStep >= focus.kindStep)) focus = n;
        }
        requestAnimationFrame(() => {
            if (oldScroll !== undefined) scroll.scrollLeft = oldScroll;
            if (!focus) return;
            const x = pos.at[focus.id].x;
            if (x < scroll.scrollLeft + 30 || x > scroll.scrollLeft + scroll.clientWidth - 30) {
                scroll.scrollLeft = Math.max(0, x - scroll.clientWidth / 2);
            }
        });
        return block;
    }
}

/** A branch-and-bound node at step k: generated, live (in the list), taken out, expanded or pruned. */
export function bnbStateAt(n, k) {
    if (n.firstStep > k) return "hidden";
    if (n.kind === "pruned" && n.kindStep <= k) return "pruned";
    if (n.kind === "expanded" && n.kindStep <= k) return "expanded";
    if (n.pollStep != null && n.pollStep <= k) return "taken";
    if (n.liveStep != null && n.liveStep <= k) return "live";
    return "generated";
}

/** The best solution so far at step k ("none yet" before the first improvement). */
function bestAt(tree, k) {
    let value = null;
    for (const u of tree.bestUpdates ?? []) if (u.step <= k) value = u.value;
    return value;
}

function line(label, content) {
    const row = document.createElement("div");
    row.className = "graph-line";
    const l = document.createElement("span");
    l.className = "graph-line-label";
    l.textContent = label;
    const v = document.createElement("code");
    v.textContent = content;
    row.append(l, v);
    return row;
}

function bnbCounts(tree, states, current, k) {
    const shown = tree.nodes.filter((n) => states[n.id] !== "hidden");
    const count = (s) => shown.filter((n) => states[n.id] === s).length;
    const box = document.createElement("div");
    box.className = "graph-state";
    box.append(line("so far", [`${shown.length} generated`, `${count("expanded")} expanded`, `${count("pruned")} pruned`,
        `${count("live")} live`].join(" · ")));
    const best = bestAt(tree, k);
    box.append(line(`best so far (${tree.bestName})`, best == null ? "none yet: no complete solution found" : best));
    const live = shown.filter((n) => states[n.id] === "live");
    if (tree.assignmentShape === "priority") {
        const v = (n) => Number.parseFloat(n.label);
        live.sort((a, b) => (tree.maximise === false ? v(a) - v(b) : v(b) - v(a)));
    } else if (tree.assignmentShape === "stack") live.reverse();
    box.append(line(`live (${tree.assignment})`, live.length ? live.map((n) => n.label).join(", ") + (live.length > 1 ? "   ← next taken first" : "") : "empty"));
    if (current) box.append(line(states[current.id] === "pruned" ? "dropped" : "taken out", current.choice));
    return box;
}

/** What a node looks like at step k: not visible yet, running (active), or its final kind. */
export function stateAt(n, k) {
    if (n.firstStep > k) return "hidden";
    if (n.kindStep <= k) return n.kind;
    if (n.endStep < 0 || k <= n.endStep) return "active";
    return "visited";
}

function counts(tree, states, current, k) {
    if (isRecursion(tree)) return recursionCounts(tree, states);
    const tally = { active: 0, explored: 0, solution: 0, "dead-end": 0, pruned: 0, rejected: 0 };
    for (const n of tree.nodes) if (states[n.id] in tally) tally[states[n.id]]++;
    const p = document.createElement("div");
    p.className = "graph-state";
    const calls = tree.nodes.filter((n) => states[n.id] !== "hidden" && states[n.id] !== "rejected").length;
    const parts = [`${calls} call${calls === 1 ? "" : "s"} so far`];
    if (tally.solution) parts.push(`${tally.solution} solution${tally.solution === 1 ? "" : "s"}`);
    if (tally.rejected + tally.pruned) parts.push(`${tally.rejected + tally.pruned} pruned`);
    if (tally["dead-end"]) parts.push(`${tally["dead-end"]} dead end${tally["dead-end"] === 1 ? "" : "s"}`);
    const row = document.createElement("div");
    row.className = "graph-line";
    const label = document.createElement("span");
    label.className = "graph-line-label";
    label.textContent = "so far";
    const value = document.createElement("code");
    value.textContent = parts.join(" · ");
    row.append(label, value);
    p.append(row);
    if (current) {
        const path = [];
        for (let n = current; n && n.parent != null; n = tree.nodes[n.parent]) path.unshift(n.choice);
        const r2 = document.createElement("div");
        r2.className = "graph-line";
        const l2 = document.createElement("span");
        l2.className = "graph-line-label";
        l2.textContent = "current path";
        const v2 = document.createElement("code");
        v2.textContent = path.length ? path.join(" → ") : "the first call (no choice made yet)";
        r2.append(l2, v2);
        p.append(r2);
    }
    if (tree.bestName) {
        const best = bestAt(tree, k);
        p.append(line(`best so far (${tree.bestName})`, best == null ? "none yet: no complete solution found" : best));
    }
    return p;
}

function recursionCounts(tree, states) {
    const shown = tree.nodes.filter((n) => states[n.id] !== "hidden");
    const count = (kind) => shown.filter((n) => states[n.id] === kind).length;
    const parts = [`${shown.length} call${shown.length === 1 ? "" : "s"} so far`];
    if (tree.kind === "memo") parts.push(`${count("hit")} answered from the memo`);
    else parts.push(`${count("repeat")} repeated (already computed before)`);
    const box = document.createElement("div");
    box.className = "graph-state";
    const row = document.createElement("div");
    row.className = "graph-line";
    const label = document.createElement("span");
    label.className = "graph-line-label";
    label.textContent = "so far";
    const value = document.createElement("code");
    value.textContent = parts.join(" · ");
    row.append(label, value);
    box.append(row);
    return box;
}

function legend(tree) {
    const p = document.createElement("div");
    p.className = "legend graph-legend";
    if (isRecursion(tree)) {
        const items = [["active", "Current call"], ["path", "Calls waiting for it"], ["computed", "Computed"],
            ["base", "Base case"], tree.kind === "memo" ? ["hit", "Memo hit: answered without recursing"]
                : ["repeat", "Repeated call (wasted work)"]];
        for (const [cls, label] of items) {
            const span = document.createElement("span");
            const swatch = document.createElement("i");
            swatch.className = `swatch search-${cls}`;
            span.append(swatch, label);
            p.append(span);
        }
        return p;
    }
    if (isBnb(tree)) {
        const items = [["active", "Taken out, being examined"], ["live", "Live: waiting in the list"],
            ["expanded", "Expanded"], ["pruned", "Pruned by the bound"], ["generated", "Generated, not added yet"]];
        for (const [cls, label] of items) {
            const span = document.createElement("span");
            const swatch = document.createElement("i");
            swatch.className = `swatch search-${cls}`;
            span.append(swatch, label);
            p.append(span);
        }
        const star = document.createElement("span");
        star.textContent = "★ improved the best solution";
        const hint = document.createElement("span");
        hint.textContent = "Click a node to jump to when it was generated";
        p.append(star, hint);
        return p;
    }
    const items = [["active", "Current call"], ["path", "Path to it"], ["solution", "Solution"],
        ["dead-end", "Dead end"], ["rejected", "Rejected by the check"]];
    if (tree.nodes.some((n) => n.kind === "pruned")) items.push(["pruned", "Cut by a bound check"]);
    for (const [cls, label] of items) {
        const span = document.createElement("span");
        const swatch = document.createElement("i");
        swatch.className = `swatch search-${cls}`;
        span.append(swatch, label);
        p.append(span);
    }
    if (tree.kind === "binary") {
        const span = document.createElement("span");
        span.textContent = "+x take x, −x leave x";
        p.append(span);
    }
    const hint = document.createElement("span");
    hint.textContent = "Click a node to jump to its call";
    p.append(hint);
    return p;
}

function describe(tree, states) {
    const shown = tree.nodes.filter((n) => states[n.id] !== "hidden").length;
    return `${isBnb(tree) ? "Branch-and-bound" : "State-space"} tree of ${tree.method}: ${shown} nodes so far`;
}

/** Tidy layout: leaves left to right in creation order, parents centred over their children. */
function layout(tree) {
    const children = tree.nodes.map(() => []);
    for (const n of tree.nodes) if (n.parent != null) children[n.parent].push(n.id);
    const at = [];
    let nextX = PAD;
    let lastWidth = 0;
    let depth = 0;
    const place = (id) => {
        const n = tree.nodes[id];
        depth = Math.max(depth, n.level);
        const kids = children[id];
        let x;
        if (kids.length === 0) {
            const width = n.kind === "rejected" ? DX_REJECTED : DX;
            nextX += lastWidth === 0 ? 0 : (lastWidth + width) / 2;
            x = nextX;
            lastWidth = width;
        } else {
            kids.forEach(place);
            x = (at[kids[0]].x + at[kids[kids.length - 1]].x) / 2;
        }
        at[id] = { x, y: TOP + n.level * DY };
    };
    // Iterative-safe for the sizes we allow (≤ 1500 nodes, depth ≤ the step limit's recursion).
    tree.nodes.filter((n) => n.parent == null).forEach((root) => place(root.id));
    return { at, width: nextX + PAD, depth };
}
