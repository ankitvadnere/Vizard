// The program's own binary trees, laid out with each node's x = its inorder position and
// y = its depth, so a binary search tree reads sorted left to right.
// The node the current method is looking at is filled amber; the nodes the methods below it on
// the call stack are looking at (the path a recursion took) are outlined; variables label nodes.

import { nodeLabel } from "../values.js";
import { el, fitted, svgRoot, text } from "./svg.js";

const R = 18;
const DX = 46;
const DY = 58;
const MAX_NODES = 63;

/**
 * @param nodes    heap objects with role "tree-node"
 * @param pointers Map<node id, variable names> for the current method
 */
export function drawTrees(nodes, pointers, nullPointers, step, previous) {
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const child = (n, i) => {
        const f = n.fields.find((x) => x.name === n.links[i]);
        return f?.value.kind === "ref" ? byId.get(f.value.ref) ?? null : null;
    };
    const hasParent = new Set();
    for (const n of nodes) for (const i of [0, 1]) { const c = child(n, i); if (c) hasParent.add(c.id); }
    const roots = nodes.filter((n) => !hasParent.has(n.id));

    const current = new Set(pointers.keys());
    const onStack = new Set();
    for (const frame of step.stack.slice(1)) {
        for (const v of frame.variables) if (v.value.kind === "ref" && byId.has(v.value.ref)) onStack.add(v.value.ref);
    }

    const wrapper = document.createElement("div");
    wrapper.className = "array-block";
    const title = document.createElement("div");
    title.className = "array-title";
    title.textContent = roots.length > 1 ? "Binary trees" : "Binary tree";
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = `${nodes[0].type} nodes · ${nodes.length} ${nodes.length === 1 ? "node" : "nodes"}`;
    title.append(meta);
    wrapper.append(title);

    let budget = MAX_NODES;
    for (const root of roots) {
        const placed = [];
        const seen = new Set();
        let order = 0;
        const layout = (n, depth) => {
            if (!n || seen.has(n.id) || budget <= 0) return;
            seen.add(n.id);
            budget--;
            layout(child(n, 0), depth + 1);
            placed.push({ node: n, x: order++, depth });
            layout(child(n, 1), depth + 1);
        };
        layout(root, 0);
        wrapper.append(drawOne(placed, child, pointers, current, onStack, step, previous));
    }
    if (nullPointers.length > 0) {
        const p = document.createElement("p");
        p.className = "viz-note";
        p.textContent = `${nullPointers.join(", ")} ${nullPointers.length === 1 ? "is" : "are"} null`;
        wrapper.append(p);
    }
    const legend = document.createElement("p");
    legend.className = "viz-note";
    legend.textContent = onStack.size
        ? "Filled: the node this method is at. Outlined: nodes the calls below it are at (the path so far)."
        : "Filled: the node a variable of this method points to.";
    wrapper.append(legend);
    return wrapper;
}

function drawOne(placed, child, pointers, current, onStack, step, previous) {
    const at = new Map(placed.map((p) => [p.node.id, p]));
    const depth = Math.max(0, ...placed.map((p) => p.depth));
    const labelRoom = 16;
    const width = 16 + placed.length * DX + 40;
    const height = labelRoom + R + depth * DY + R + 12;
    const svg = svgRoot(width, height, `Binary tree, inorder: ${placed.map((p) => nodeLabel(p.node, step.heap)).join(", ")}`);
    const cx = (p) => 16 + R + p.x * DX;
    const cy = (p) => labelRoom + R + p.depth * DY;

    for (const p of placed) {
        for (const i of [0, 1]) {
            const c = child(p.node, i);
            const q = c ? at.get(c.id) : null;
            if (!q) continue;
            const old = previous?.heap?.[p.node.id];
            const oldRef = old?.fields.find((f) => f.name === p.node.links[i])?.value;
            const isNewLink = previous && old && !(oldRef?.kind === "ref" && oldRef.ref === c.id);
            svg.append(el("line", { class: `tree-edge${isNewLink ? " hot" : ""}`, x1: cx(p), y1: cy(p) + R, x2: cx(q), y2: cy(q) - R }));
        }
    }
    for (const p of placed) {
        const id = p.node.id;
        const isNew = previous && !previous.heap?.[id];
        const cls = ["tree-node", current.has(id) ? "current" : "", onStack.has(id) && !current.has(id) ? "on-stack" : "",
            isNew ? "changed flash" : ""].filter(Boolean).join(" ");
        const g = el("g", { class: cls });
        g.append(el("circle", { cx: cx(p), cy: cy(p), r: R }));
        g.append(fitted(null, cx(p), cy(p), nodeLabel(p.node, step.heap), 4, "middle"));
        svg.append(g);
        const names = pointers.get(id) ?? [];
        if (names.length) {
            svg.append(text("node-pointer", cx(p) + R + 4, cy(p) - R + 4, names.join(", "), "start"));
        }
    }
    const scroll = document.createElement("div");
    scroll.className = "array-scroll";
    scroll.append(svg);
    return scroll;
}
