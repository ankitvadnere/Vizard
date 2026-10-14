// Draws dynamic-programming tables as they fill: the cell being computed (amber), the cells it reads
// (blue, with arrows into the cell being computed: the recurrence made visible), cells already filled,
// cells not reached yet (faded), and base values the program never had to compute (the zero row of a
// knapsack table). Below the table, the line's expression with the values it reads.
//
// The fill order comes from analysis.dpTables (every write with the step its value appears); the values
// themselves are read from the program's array at the current step.

import { el, svgRoot, text, arrowMarker } from "./svg.js";

const CELL = 38;
const HEAD = 30;
const MAX_CELLS = 600;

const PROBLEM = {
    fibonacci: "Fibonacci", knapsack: "0/1 knapsack", lcs: "longest common subsequence",
    "edit-distance": "edit distance", "coin-change": "coin change", "matrix-chain": "matrix chain", dp: "DP table",
};

export class DpTableVisualizer {
    constructor(container) {
        this.container = container;
        this.tables = [];
    }

    /** Once per trace: the tables, and the cells each one only ever reads (its base values). */
    load(analysis, steps) {
        this.tables = (analysis?.dpTables ?? []).map((t) => {
            const written = new Set(t.writes.map((w) => `${w.row},${w.col}`));
            const base = new Set();
            for (const s of steps) {
                const dp = s.insight?.dp;
                if (dp?.table !== t.table) continue;
                for (const [r, c] of dp.reads) if (!written.has(`${r},${c}`)) base.add(`${r},${c}`);
            }
            return { ...t, base };
        });
    }

    /** Draws the tables reachable at this step; returns the heap ids drawn (so they aren't drawn twice). */
    render(step) {
        this.container.replaceChildren();
        const drawn = [];
        for (const t of this.tables) {
            const found = locate(t, step);
            if (!found) continue;
            const block = drawTable(t, found.ref, found.array, step);
            if (block) {
                this.container.append(block);
                drawn.push(found.ref);
            }
        }
        return drawn;
    }
}

function locate(t, step) {
    const dp = step.insight?.dp;
    if (dp?.table === t.table && step.heap[dp.ref]) return { ref: dp.ref, array: step.heap[dp.ref] };
    for (const frame of step.stack) {
        if (frame.methodName !== t.method) continue;
        const v = frame.variables.find((x) => x.name === t.table && x.value.kind === "ref");
        if (v && step.heap[v.value.ref]) return { ref: v.value.ref, array: step.heap[v.value.ref] };
    }
    return null;
}

function drawTable(t, ref, array, step) {
    const k = step.index;
    const rows = t.dims === 2 ? array.elements.map((e) => (e.kind === "ref" ? step.heap[e.ref] : null)) : [array];
    const nRows = rows.length;
    const nCols = Math.max(0, ...rows.map((r) => r?.elements.length ?? 0));
    if (nRows * nCols === 0 || nRows * nCols > MAX_CELLS) return null;

    const filled = new Set();
    const fresh = new Set();
    for (const w of t.writes) {
        if (w.step <= k) filled.add(`${w.row},${w.col}`);
        if (w.step === k) fresh.add(`${w.row},${w.col}`);
    }
    const dp = step.insight?.dp?.table === t.table && step.insight.dp.ref === ref ? step.insight.dp : null;
    const current = dp ? dp.cell.join(",") : null;
    const reads = new Set((dp?.reads ?? []).map((c) => c.join(",")));

    const hasRowLabels = t.rowLabels?.length;
    const hasColLabels = t.colLabels?.length;
    const left = HEAD + (hasRowLabels ? 18 : 0);
    const top = HEAD + (hasColLabels ? 16 : 0);
    const width = left + nCols * CELL + 6;
    const height = top + nRows * CELL + 6;
    const svg = svgRoot(width, height, `Table ${t.table}: ${filled.size} of ${nRows * nCols} cells filled`);
    svg.classList.add("dp-svg");
    const arrow = arrowMarker(svg, "dp-arrowhead");
    const cx = (c) => left + c * CELL + CELL / 2;
    const cy = (r) => top + r * CELL + CELL / 2;

    // Axis names in the corner, then column and row headers.
    svg.append(text("dp-axis", 4, 12, t.dims === 2 ? `${t.rowVar ?? "row"} \\ ${t.colVar ?? "col"}` : t.colVar ?? "", "start"));
    for (let c = 0; c < nCols; c++) {
        svg.append(text("dp-index", cx(c), top - 8, String(c), "middle"));
        if (hasColLabels) svg.append(text("dp-letter", cx(c), top - 22, t.colLabels[c] ?? "", "middle"));
    }
    if (t.dims === 2) {
        for (let r = 0; r < nRows; r++) {
            svg.append(text("dp-index", left - 10, cy(r), String(r), "end"));
            if (hasRowLabels) svg.append(text("dp-letter", left - 28, cy(r), t.rowLabels[r] ?? "", "middle"));
        }
    }

    rows.forEach((row, r) => {
        row?.elements.forEach((v, c) => {
            const key = `${r},${c}`;
            const state = key === current ? "current"
                : reads.has(key) ? "read"
                    : filled.has(key) ? "filled"
                        : t.base.has(key) ? "base" : "pending";
            const g = el("g", { class: `dp-cell ${state}${fresh.has(key) ? " fresh" : ""}` });
            g.append(el("rect", { x: left + c * CELL + 1, y: top + r * CELL + 1, width: CELL - 2, height: CELL - 2, rx: 4 }));
            g.append(text(null, cx(c), cy(r), show(v, t, state), "middle"));
            g.append(el("title", {}, `${t.table}${t.dims === 2 ? `[${r}]` : ""}[${c}] = ${v.display}`));
            svg.append(g);
        });
    });

    // Arrows from each cell read into the cell being computed.
    if (dp) {
        const [tr, tc] = dp.cell;
        for (const [r, c] of dp.reads) {
            if (`${r},${c}` === current || r >= nRows || c >= nCols) continue;
            const x1 = cx(c);
            const y1 = cy(r);
            const x2 = cx(tc);
            const y2 = cy(tr);
            const len = Math.hypot(x2 - x1, y2 - y1) || 1;
            const ux = (x2 - x1) / len;
            const uy = (y2 - y1) / len;
            const sx = x1 + ux * 12;
            const sy = y1 + uy * 12;
            const ex = x2 - ux * 15;
            const ey = y2 - uy * 15;
            const bend = Math.min(len * 0.22, 42); // long arrows curve more, clear of the cells between
            const mx = (sx + ex) / 2 - uy * bend;
            const my = (sy + ey) / 2 + ux * bend;
            svg.append(el("path", { class: "dp-arrow", d: `M${sx} ${sy} Q${mx} ${my} ${ex} ${ey}`, "marker-end": arrow }));
        }
    }

    const block = document.createElement("div");
    block.className = "array-block dp-block";
    const title = document.createElement("div");
    title.className = "array-title";
    title.textContent = t.table;
    const meta = document.createElement("span");
    meta.className = "meta";
    const what = t.kind === "memo" ? `memo of ${t.method}()` : `${PROBLEM[t.problem] ?? "DP"} table`;
    meta.textContent = `${what} · ${filled.size} of ${nRows * nCols} cells filled`;
    title.append(meta);
    block.append(title);
    const scroll = document.createElement("div");
    scroll.className = "array-scroll";
    scroll.append(svg);
    block.append(scroll);
    if (dp) block.append(formula(dp));
    block.append(legend(t));
    return block;
}

function show(v, t, state) {
    const d = v.display;
    if (d === "2147483647" || d === "9223372036854775807") return "∞";
    if (state === "pending" && t.kind === "memo" && d === "-1") return "·";
    return d.length > 5 ? `${d.slice(0, 4)}…` : d;
}

/** "dp[i][w] = max(dp[i-1][w], …)" / "= max(15, 15 + 30)" / "→ 45". */
function formula(dp) {
    const box = document.createElement("div");
    box.className = "dp-formula";
    const line = (cls, content) => {
        const p = document.createElement("div");
        p.className = cls;
        p.textContent = content;
        box.append(p);
    };
    // The cell written, with its actual indices: dp[2][4] (written dp[i][w] in the code).
    let at = null;
    if (dp.write) {
        const name = dp.label.slice(0, dp.label.indexOf("["));
        const twoD = dp.label.split("[").length > 2;
        at = twoD ? `${name}[${dp.cell[0]}][${dp.cell[1]}]` : `${name}[${dp.cell[1]}]`;
    }
    const lhs = dp.label === "check" ? "check" : at ?? dp.label;
    line("dp-written", `${lhs}${dp.label === "check" ? ": " : " = "}${dp.formula}`);
    if (dp.values && dp.values !== dp.formula) line("dp-values", `${dp.label === "check" ? "" : "= "}${dp.values}`);
    if (dp.result != null) line("dp-result", `→ ${dp.result}`);
    return box;
}

function legend(t) {
    const p = document.createElement("div");
    p.className = "legend graph-legend";
    const items = [["current", "Being computed"], ["read", "Read by this line"], ["filled", "Filled"],
        ["pending", "Not computed yet"]];
    if (t.base.size) items.push(["base", "Base value (never computed)"]);
    for (const [cls, label] of items) {
        const span = document.createElement("span");
        const swatch = document.createElement("i");
        swatch.className = `swatch dp-${cls}`;
        span.append(swatch, label);
        p.append(span);
    }
    return p;
}
