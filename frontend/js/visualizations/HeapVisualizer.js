// A PriorityQueue is a binary heap stored in an array: element i has children 2i + 1 and 2i + 2,
// and every parent is <= its children, so the root is the next element poll() returns.
// Drawn twice: as the tree the array encodes, and as the array itself.

import { cellValue } from "../values.js";
import { el, fitted, svgRoot, text, block } from "./svg.js";
import { before } from "./visible.js";

const R = 17;
const LEVEL = 50;
const SLOT = 46;      // width per leaf position
const CELL = 40;
const MAX_NODES = 31; // five levels

export function drawHeap({ names, object }, step, previous) {
    const items = object.elements.slice(0, MAX_NODES);
    const old = before(previous, object);
    const changed = (i) => !old || i >= old.elements.length
        || cellValue(old.elements[i], previous.heap) !== cellValue(items[i], step.heap);

    const levels = items.length === 0 ? 1 : Math.floor(Math.log2(items.length)) + 1;
    const treeWidth = Math.max(2 ** (levels - 1) * SLOT, items.length * CELL);
    const top = 14;
    const arrayY = top + (levels - 1) * LEVEL + 2 * R + 30;
    const width = treeWidth + 16;
    const height = arrayY + CELL + 22;
    const svg = svgRoot(width, height, `${names.join(", ")}: priority queue (binary heap): ${items.map((v) => cellValue(v, step.heap)).join(", ")}`);

    const pos = (i) => {
        const depth = Math.floor(Math.log2(i + 1));
        const indexInLevel = i - (2 ** depth - 1);
        const slot = treeWidth / 2 ** depth;
        return { x: 8 + slot * (indexInLevel + 0.5), y: top + depth * LEVEL + R };
    };

    if (items.length === 0) {
        svg.append(text("empty-note", width / 2, top + R, "empty", "middle"));
    }
    items.forEach((_, i) => {
        if (i === 0) return;
        const a = pos(Math.floor((i - 1) / 2));
        const b = pos(i);
        svg.append(el("line", { class: "tree-edge", x1: a.x, y1: a.y + R, x2: b.x, y2: b.y - R }));
    });
    items.forEach((value, i) => {
        const p = pos(i);
        const g = el("g", { class: `tree-node${changed(i) ? " changed" : ""}${i === 0 ? " root" : ""}` });
        g.append(el("circle", { cx: p.x, cy: p.y, r: R }));
        g.append(fitted(null, p.x, p.y, cellValue(value, step.heap), 4, "middle"));
        svg.append(g);
    });
    if (items.length > 0) {
        const root = pos(0);
        svg.append(text("side-note", root.x + R + 8, root.y, "next poll()", "start"));
    }

    items.forEach((value, i) => {
        const x = 8 + i * CELL;
        svg.append(text("index", x + CELL / 2 - 2, arrayY - 5, String(i)));
        const g = el("g", { class: `cell${changed(i) ? " changed" : ""}` });
        g.append(el("rect", { x, y: arrayY, width: CELL - 4, height: CELL - 6, rx: 5 }));
        g.append(fitted(null, x + CELL / 2 - 2, arrayY + (CELL - 6) / 2, cellValue(value, step.heap), 4, "middle"));
        svg.append(g);
    });
    const more = object.length - items.length;
    if (more > 0) svg.append(text("more", 8 + items.length * CELL, arrayY + 16, `+${more} more`, "start"));

    return block(names, `PriorityQueue, a binary min-heap · size ${object.length}`, svg);
}
