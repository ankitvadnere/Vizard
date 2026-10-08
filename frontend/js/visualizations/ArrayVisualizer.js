// Draws every array the current method can see as a row of SVG boxes:
//   index numbers above, values inside, index variables (i, j, mid...) as carets below.
// Cell styles come from the step's insight: compared / read / written by this line / just changed.
// Rendering is a pure function of the step; the swap animation is only a transition into it.

import { formatValue } from "../values.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const CELL = 46;          // cell width and height
const GAP = 4;
const STEP = CELL + GAP;
const TOP = 16;           // room for index numbers
const POINTER_LINE = 14;  // height of one pointer name
const MAX_TEXT = 6;       // characters shown inside a cell

export class ArrayVisualizer {
    constructor(container) {
        this.container = container;
    }

    /** Draws the arrays and returns how many there were. */
    render(step, { animate }) {
        this.container.replaceChildren();
        const arrays = visibleArrays(step);
        const insight = step.insight ?? {};
        for (const entry of arrays) {
            const block = isMatrix(entry.object, step.heap)
                ? this.#matrixBlock(entry, step, insight)
                : this.#arrayBlock(entry, step, insight, animate);
            this.container.append(block);
        }
        return arrays.length;
    }

    #arrayBlock({ names, object }, step, insight, animate) {
        const ref = object.id;
        const n = object.elements.length;
        const marks = cellMarks(ref, insight);
        const pointers = groupPointers(ref, insight);

        const leftGhost = pointers.has(-1);
        const rightGhost = pointers.has(object.length) && !object.truncated;
        const originX = leftGhost ? STEP : 0;
        const maxNames = Math.max(0, ...[...pointers.values()].map((v) => v.length));
        const width = originX + n * STEP + (rightGhost ? STEP : 0) + (object.truncated ? 70 : 0);
        const height = TOP + CELL + (maxNames ? 12 + maxNames * POINTER_LINE : 4);

        const svg = el("svg", { class: "array-svg", width, height, viewBox: `0 0 ${width} ${height}`, role: "img" });
        svg.setAttribute("aria-label", `${names.join(", ")}: ${object.elements.map((e) => formatValue(e, step.heap, 1)).join(", ")}`);

        const cells = [];
        object.elements.forEach((element, i) => {
            const x = originX + i * STEP;
            svg.append(text("index", x + CELL / 2, 11, String(i)));
            const g = el("g", { class: `cell ${marks.get(i) ?? ""}` });
            g.append(el("rect", { x, y: TOP, width: CELL, height: CELL, rx: 5 }));
            const full = formatValue(element, step.heap, 1);
            const shown = full.length > MAX_TEXT ? `${full.slice(0, MAX_TEXT - 1)}…` : full;
            if (full.length > 3) g.classList.add("small");
            const label = text(null, x + CELL / 2, TOP + CELL / 2, shown);
            if (shown !== full) label.append(el("title", {}, full));
            g.append(label);
            svg.append(g);
            cells[i] = g;
        });

        if (leftGhost) svg.append(el("rect", { class: "ghost", x: 0, y: TOP, width: CELL, height: CELL, rx: 5 }));
        if (rightGhost) svg.append(el("rect", { class: "ghost", x: originX + n * STEP, y: TOP, width: CELL, height: CELL, rx: 5 }));
        if (object.truncated) {
            svg.append(text("more", originX + n * STEP + 4, TOP + CELL / 2, `+${object.length - n} more`, "start"));
        }

        for (const [index, variables] of pointers) {
            const cx = originX + index * STEP + CELL / 2;
            const y = TOP + CELL + 4;
            svg.append(el("path", { class: "pointer-caret", d: `M${cx} ${y} l5 7 h-10 z` }));
            variables.forEach((name, k) => {
                svg.append(text("pointer-name", cx, y + 18 + k * POINTER_LINE, name));
            });
        }

        if (animate) animateCells(cells, marks, ref, insight.swap);

        const block = document.createElement("div");
        block.className = "array-block";
        block.append(title(names, object.type.replace("[]", `[${object.length}]`)));
        const scroll = document.createElement("div");
        scroll.className = "array-scroll";
        scroll.append(svg);
        block.append(scroll);
        return block;
    }

    /** int[][] and friends: one row per inner array; accesses/changes apply to the row arrays. */
    #matrixBlock({ names, object }, step, insight) {
        const rows = object.elements.map((e) => (e.kind === "ref" ? step.heap[e.ref] : null));
        const columns = Math.max(0, ...rows.map((r) => r?.elements.length ?? 0));
        const left = 28;
        const width = left + columns * STEP;
        const height = TOP + rows.length * STEP;
        const svg = el("svg", { class: "array-svg", width, height, viewBox: `0 0 ${width} ${height}` });

        for (let c = 0; c < columns; c++) svg.append(text("index", left + c * STEP + CELL / 2, 11, String(c)));
        rows.forEach((row, r) => {
            const y = TOP + r * STEP;
            svg.append(text("row-label", left - 8, y + CELL / 2, String(r)));
            if (!row) return;
            const marks = cellMarks(row.id, insight);
            row.elements.forEach((element, c) => {
                const x = left + c * STEP;
                const g = el("g", { class: `cell ${marks.get(c) ?? ""}` });
                g.append(el("rect", { x, y, width: CELL, height: CELL, rx: 5 }));
                const full = formatValue(element, step.heap, 1);
                g.append(text(null, x + CELL / 2, y + CELL / 2, full.length > MAX_TEXT ? `${full.slice(0, MAX_TEXT - 1)}…` : full));
                if (full.length > 3) g.classList.add("small");
                svg.append(g);
            });
        });

        const block = document.createElement("div");
        block.className = "array-block";
        block.append(title(names, object.type.replace("[]", `[${object.length}]`)));
        const scroll = document.createElement("div");
        scroll.className = "array-scroll";
        scroll.append(svg);
        block.append(scroll);
        return block;
    }
}

// ---------- data from the step ----------

/** Arrays referenced by the current method's variables and by static fields; aliases share one drawing. */
function visibleArrays(step) {
    const byRef = new Map();
    const variables = [...step.stack[0].variables, ...step.statics];
    for (const v of variables) {
        if (v.value.kind !== "ref") continue;
        const object = step.heap[v.value.ref];
        if (!object || object.kind !== "array") continue;
        if (v.name === "args" && object.length === 0) continue; // main's empty String[] args
        const entry = byRef.get(object.id) ?? { names: [], object };
        entry.names.push(v.name);
        byRef.set(object.id, entry);
    }
    return [...byRef.values()];
}

function isMatrix(object, heap) {
    const refs = object.elements.filter((e) => e.kind === "ref");
    return refs.length > 0 && refs.length === object.elements.filter((e) => e.kind !== "null").length
        && refs.every((e) => heap[e.ref]?.kind === "array");
}

/** index → css class. Later rules win: changed > target > compared > read. */
function cellMarks(ref, insight) {
    const marks = new Map();
    for (const a of insight.accesses ?? []) {
        if (a.ref !== ref) continue;
        if (a.compared) marks.set(a.index, "compared");
        else if (a.write) marks.set(a.index, "target");
        else if (!marks.has(a.index)) marks.set(a.index, "read");
    }
    for (const c of insight.changes ?? []) {
        if (c.ref !== ref) continue;
        for (const i of c.indices) marks.set(i, "changed");
    }
    if (insight.swap?.ref === ref) {
        // A swap finishes over several lines; show both cells as changed on the step that completes it.
        marks.set(insight.swap.first, "changed");
        marks.set(insight.swap.second, "changed");
    }
    return marks;
}

/** index → [variable names] */
function groupPointers(ref, insight) {
    const byIndex = new Map();
    for (const p of insight.pointers ?? []) {
        if (p.ref !== ref) continue;
        const list = byIndex.get(p.index) ?? [];
        list.push(p.variable);
        byIndex.set(p.index, list);
    }
    return byIndex;
}

// ---------- animation ----------

function animateCells(cells, marks, ref, swap) {
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    if (swap && swap.ref === ref && cells[swap.first] && cells[swap.second]) {
        // The two values arrive from each other's place along an arc.
        const distance = (swap.second - swap.first) * STEP;
        arc(cells[swap.first], distance, -1);
        arc(cells[swap.second], -distance, 1);
        return;
    }
    for (const [i, mark] of marks) {
        if (mark === "changed" && cells[i]) {
            cells[i].classList.add("flash");
        }
    }
}

function arc(cell, fromX, direction) {
    if (typeof cell.animate !== "function") return;
    cell.animate([
        { transform: `translate(${fromX}px, 0px)` },
        { transform: `translate(${fromX / 2}px, ${direction * 22}px)` },
        { transform: "translate(0px, 0px)" },
    ], { duration: 480, easing: "ease-in-out" });
}

// ---------- DOM helpers ----------

function el(name, attributes = {}, content) {
    const node = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
    if (content !== undefined) node.textContent = content;
    return node;
}

function text(className, x, y, content, anchor) {
    const node = el("text", { x, y });
    if (className) node.setAttribute("class", className);
    if (anchor) node.setAttribute("text-anchor", anchor);
    node.textContent = content;
    return node;
}

function title(names, meta) {
    const div = document.createElement("div");
    div.className = "array-title";
    div.textContent = names.join(", ");
    const span = document.createElement("span");
    span.className = "meta";
    span.textContent = meta;
    div.append(span);
    return div;
}
