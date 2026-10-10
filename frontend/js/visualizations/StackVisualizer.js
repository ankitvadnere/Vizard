// A stack drawn upright: the top at the top, whichever end the class keeps it at.
// Pushed elements are highlighted; popped ones stay one step as faded ghosts above the top.

import { cellValue } from "../values.js";
import { el, fitted, svgRoot, text, block } from "./svg.js";
import { before, operationsOn, topFirst } from "./visible.js";

const W = 104;          // cell width
const H = 32;           // cell height
const GAP = 3;
const LEFT = 52;        // room for the "top →" label
const MAX_SHOWN = 10;

export function drawStack({ names, object }, step, previous) {
    const items = topFirst(object);
    const shown = items.slice(0, MAX_SHOWN);
    const pushed = Math.min(operationsOn(step, object, "push").length, shown.length);
    const popCount = operationsOn(step, object, "pop").length;
    const old = before(previous, object);
    const ghosts = popCount > 0 && old ? topFirst(old).slice(0, popCount).slice(0, 3) : [];

    const rows = ghosts.length + Math.max(shown.length, 1);
    const more = items.length - shown.length;
    const height = 8 + rows * (H + GAP) + (more > 0 ? 20 : 0) + 10;
    const width = LEFT + W + 96;
    const svg = svgRoot(width, height, `${names.join(", ")}: stack, top first: ${items.map((v) => cellValue(v, step.heap)).join(", ")}`);

    let y = 8;
    ghosts.forEach((value) => {
        svg.append(el("rect", { x: LEFT, y, width: W, height: H, rx: 5, class: "ghost-cell" }));
        svg.append(fitted("ghost-text", LEFT + W / 2, y + H / 2, cellValue(value, step.heap), 12, "middle"));
        svg.append(text("side-note", LEFT + W + 10, y + H / 2, "popped", "start"));
        y += H + GAP;
    });

    if (shown.length === 0) {
        svg.append(text("empty-note", LEFT + W / 2, y + H / 2, "empty", "middle"));
        y += H + GAP;
    }
    shown.forEach((value, i) => {
        const g = el("g", { class: `cell${i < pushed ? " changed" + (i === 0 ? " flash" : "") : ""}` });
        g.append(el("rect", { x: LEFT, y, width: W, height: H, rx: 5 }));
        g.append(fitted(null, LEFT + W / 2, y + H / 2, cellValue(value, step.heap), 12, "middle"));
        svg.append(g);
        if (i === 0) {
            svg.append(text("end-label", LEFT - 8, y + H / 2, "top", "end"));
            svg.append(el("path", { class: "end-caret", d: `M${LEFT - 6} ${y + H / 2 - 5} l6 5 l-6 5 z` }));
            if (i < pushed) svg.append(text("side-note", LEFT + W + 10, y + H / 2, "pushed", "start"));
        }
        y += H + GAP;
    });
    if (more > 0) {
        svg.append(text("more", LEFT + W / 2, y + 10, `+${more} more below`, "middle"));
        y += 20;
    }
    svg.append(el("path", { class: "stack-base", d: `M${LEFT - 6} ${y + 2} H${LEFT + W + 6}` }));

    return block(names, `${object.type}, used as a stack · size ${object.length}`, svg);
}
