// A queue drawn left to right: front (next out) on the left, rear (last in) on the right.
// Enqueued elements are highlighted; dequeued ones stay one step as faded ghosts before the front.

import { cellValue } from "../values.js";
import { el, fitted, svgRoot, text, block } from "./svg.js";
import { before, operationsOn } from "./visible.js";

const W = 58;
const H = 40;
const GAP = 4;
const STEP = W + GAP;
const MAX_SHOWN = 14;

export function drawQueue({ names, object }, step, previous) {
    const deque = object.role === "deque";
    const items = object.elements;
    const shown = items.slice(0, MAX_SHOWN);
    const added = Math.min(operationsOn(step, object, "enqueue").length, shown.length);
    const removed = operationsOn(step, object, "dequeue").length;
    const old = before(previous, object);
    const ghosts = removed > 0 && old ? old.elements.slice(0, Math.min(removed, 3)) : [];

    const originX = 8 + ghosts.length * STEP;
    const more = items.length - shown.length;
    const width = originX + Math.max(shown.length, 1) * STEP + (more > 0 ? 70 : 0) + 8;
    const height = 18 + H + 24;
    const svg = svgRoot(width, height, `${names.join(", ")}: queue, front first: ${items.map((v) => cellValue(v, step.heap)).join(", ")}`);
    const y = 18;

    ghosts.forEach((value, i) => {
        const x = 8 + i * STEP;
        svg.append(el("rect", { x, y, width: W, height: H, rx: 5, class: "ghost-cell" }));
        svg.append(fitted("ghost-text", x + W / 2, y + H / 2, cellValue(value, step.heap), 6, "middle"));
        svg.append(text("side-note", x + W / 2, y - 6, "out", "middle"));
    });
    if (shown.length === 0) {
        svg.append(text("empty-note", originX + W / 2, y + H / 2, "empty", "middle"));
    }
    shown.forEach((value, i) => {
        const x = originX + i * STEP;
        const isNew = i >= shown.length - added;
        const g = el("g", { class: `cell${isNew ? " changed" : ""}${isNew && i === shown.length - 1 ? " flash" : ""}` });
        g.append(el("rect", { x, y, width: W, height: H, rx: 5 }));
        g.append(fitted(null, x + W / 2, y + H / 2, cellValue(value, step.heap), 6, "middle"));
        svg.append(g);
    });
    if (shown.length === 1 && more === 0) {
        svg.append(text("end-label", originX + W / 2, y + H + 16, deque ? "first, last" : "front, rear", "middle"));
    } else if (shown.length > 0) {
        svg.append(text("end-label", originX + W / 2, y + H + 16, deque ? "first" : "front", "middle"));
        if (more === 0) {
            const lastX = originX + (shown.length - 1) * STEP + W / 2;
            svg.append(text("end-label", lastX, y + H + 16, deque ? "last" : "rear", "middle"));
        }
    }
    if (more > 0) {
        svg.append(text("more", originX + shown.length * STEP + 4, y + H / 2, `+${more} more`, "start"));
    }
    const role = deque ? "a deque (both ends)" : "a queue";
    return block(names, `${object.type}, used as ${role} · size ${object.length}`, svg);
}
