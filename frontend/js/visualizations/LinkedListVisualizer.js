// The program's own linked lists: [value|•] boxes joined by arrows, one row per chain.
// Variables pointing into a list (head, curr, prev...) label the node they point at, so pointer
// moves are visible step by step. A link changed by this step is drawn in amber; a new node is
// highlighted. During a reversal the list is two chains (done and still to do): two rows.

import { nodeLabel } from "../values.js";
import { arrowMarker, el, fitted, svgRoot, text } from "./svg.js";

const VALUE_W = 46;
const LINK_W = 22;
const BOX_W = VALUE_W + LINK_W;
const H = 34;
const GAP = 38;
const STEP = BOX_W + GAP;
const LABEL_LINE = 14;
const MAX_PER_ROW = 12;

/**
 * @param nodes    heap objects with role "list-node"
 * @param pointers Map<node id, variable names>
 * @param nullPointers names of node-typed variables that are null
 */
export function drawLinkedLists(nodes, pointers, nullPointers, step, previous) {
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const nextOf = (n) => linkTarget(n, n.links[0], byId);
    const prevOf = (n) => (n.links.length > 1 ? linkTarget(n, n.links[1], byId) : null);

    const pointedTo = new Set(nodes.map(nextOf).filter(Boolean).map((n) => n.id));
    const chains = [];
    const placed = new Set();
    const walk = (start) => {
        const chain = [];
        let node = start;
        while (node && !placed.has(node.id)) {
            placed.add(node.id);
            chain.push(node);
            node = nextOf(node);
        }
        return { nodes: chain, loopsTo: node && chain.includes(node) ? node : null };
    };
    for (const n of nodes) if (!pointedTo.has(n.id)) chains.push(walk(n));
    for (const n of nodes) if (!placed.has(n.id)) chains.push(walk(n)); // a circular list has no head

    const wrapper = document.createElement("div");
    wrapper.className = "array-block";
    const title = document.createElement("div");
    title.className = "array-title";
    title.textContent = "Linked list";
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = `${nodes[0].type} nodes · ${nodes.length} ${nodes.length === 1 ? "node" : "nodes"}`
        + (chains.length > 1 ? ` in ${chains.length} chains` : "");
    title.append(meta);
    wrapper.append(title);

    for (const chain of chains) {
        wrapper.append(drawChain(chain, pointers, step, previous, nextOf, prevOf));
    }
    if (nullPointers.length > 0) {
        const p = document.createElement("p");
        p.className = "viz-note";
        p.textContent = `${nullPointers.join(", ")} ${nullPointers.length === 1 ? "is" : "are"} null`;
        wrapper.append(p);
    }
    return wrapper;
}

function drawChain({ nodes, loopsTo }, pointers, step, previous, nextOf, prevOf) {
    const shown = nodes.slice(0, MAX_PER_ROW);
    const labelLines = Math.max(0, ...shown.map((n) => (pointers.get(n.id) ?? []).length));
    const top = 6 + labelLines * LABEL_LINE + (labelLines ? 8 : 0);
    const loopRoom = loopsTo ? 22 : 0;
    const width = 8 + shown.length * STEP + 48;
    const height = top + H + 14 + loopRoom;
    const svg = svgRoot(width, height, `Linked list: ${shown.map((n) => nodeLabel(n, step.heap)).join(" → ")}`);
    const arrow = arrowMarker(svg, "arrowhead");
    const hotArrow = arrowMarker(svg, "arrowhead hot");
    const x = (i) => 8 + i * STEP;

    shown.forEach((node, i) => {
        const old = previous?.heap?.[node.id];
        const isNew = previous && !old;
        const g = el("g", { class: `list-node${isNew ? " changed flash" : ""}` });
        g.append(el("rect", { x: x(i), y: top, width: VALUE_W, height: H, rx: 5 }));
        g.append(el("rect", { x: x(i) + VALUE_W, y: top, width: LINK_W, height: H, rx: 5, class: "link-cell" }));
        g.append(fitted(null, x(i) + VALUE_W / 2, top + H / 2, nodeLabel(node, step.heap), 5, "middle"));
        g.append(el("circle", { cx: x(i) + VALUE_W + LINK_W / 2, cy: top + H / 2, r: 3, class: "link-dot" }));
        svg.append(g);

        (pointers.get(node.id) ?? []).forEach((name, k) => {
            svg.append(text("pointer-name", x(i) + VALUE_W / 2, top - 8 - k * LABEL_LINE, name, "middle"));
        });
        if ((pointers.get(node.id) ?? []).length) {
            svg.append(el("path", { class: "pointer-caret", d: `M${x(i) + VALUE_W / 2} ${top - 1} l-5 -6 h10 z` }));
        }

        const next = nextOf(node);
        const changedLink = old && linkRef(old, node.links[0]) !== linkRef(node, node.links[0]);
        const sx = x(i) + VALUE_W + LINK_W / 2;
        const sy = top + H / 2;
        if (next && next === shown[i + 1]) {
            svg.append(el("path", { class: `link${changedLink ? " hot" : ""}`, d: `M${sx} ${sy} H${x(i + 1) - 2}`,
                "marker-end": changedLink ? hotArrow : arrow }));
        } else if (!next) {
            svg.append(el("path", { class: `link${changedLink ? " hot" : ""}`, d: `M${sx} ${sy} H${x(i) + BOX_W + 14}`,
                "marker-end": changedLink ? hotArrow : arrow }));
            svg.append(text(`null-label${changedLink ? " hot" : ""}`, x(i) + BOX_W + 17, sy, "null", "start"));
        } else if (next === loopsTo) {
            const tx = x(shown.indexOf(next)) + VALUE_W / 2;
            svg.append(el("path", { class: "link", d: `M${sx} ${sy} V${top + H + 14} H${tx} V${top + H + 2}`, "marker-end": arrow }));
            svg.append(text("side-note", tx + 6, top + H + 26, "back to the start: a cycle", "start"));
        }

        const prev = prevOf(node);
        if (prev && prev === shown[i - 1]) {
            svg.append(el("path", { class: "link back", d: `M${x(i) + 4} ${top + H - 6} H${x(i - 1) + BOX_W + 2}`, "marker-end": arrow }));
        }
    });
    if (nodes.length > shown.length) {
        svg.append(text("more", x(shown.length) - GAP + 6, top + H / 2, `+${nodes.length - shown.length}`, "start"));
    }
    const scroll = document.createElement("div");
    scroll.className = "array-scroll";
    scroll.append(svg);
    return scroll;
}

function linkRef(node, field) {
    const f = node.fields.find((x) => x.name === field);
    return f?.value.kind === "ref" ? f.value.ref : null;
}

function linkTarget(node, field, byId) {
    const ref = linkRef(node, field);
    return ref === null ? null : byId.get(ref) ?? null;
}

