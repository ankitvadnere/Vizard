// Small helpers for building SVG with the DOM API (no innerHTML, so values are never parsed as markup).

const SVG_NS = "http://www.w3.org/2000/svg";

export function el(name, attributes = {}, content) {
    const node = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attributes)) {
        if (value !== undefined && value !== null) node.setAttribute(key, String(value));
    }
    if (content !== undefined) node.textContent = content;
    return node;
}

export function text(className, x, y, content, anchor) {
    const node = el("text", { x, y });
    if (className) node.setAttribute("class", className);
    if (anchor) node.setAttribute("text-anchor", anchor);
    node.textContent = content;
    return node;
}

/** Shortens a value to fit a box; the full value stays available as a tooltip. */
export function fitted(className, x, y, full, maxChars, anchor) {
    const shown = full.length > maxChars ? `${full.slice(0, maxChars - 1)}…` : full;
    const node = text(className, x, y, shown, anchor);
    if (shown !== full) node.append(el("title", {}, full));
    return node;
}

export function svgRoot(width, height, label) {
    const svg = el("svg", { class: "array-svg structure-svg", width, height, viewBox: `0 0 ${width} ${height}`, role: "img" });
    if (label) svg.setAttribute("aria-label", label);
    return svg;
}

/** One arrowhead definition per drawing; ids are made unique so several SVGs can share a page. */
let arrowCount = 0;
export function arrowMarker(svg, className = "arrowhead") {
    const id = `arrow-${++arrowCount}`;
    const defs = el("defs");
    const marker = el("marker", {
        id, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse",
    });
    marker.append(el("path", { d: "M0 0L10 5L0 10z", class: className }));
    defs.append(marker);
    svg.append(defs);
    return `url(#${id})`;
}

/** Block with a title line (variable names + type) above a drawing, like the array blocks. */
export function block(names, meta, drawing) {
    const div = document.createElement("div");
    div.className = "array-block";
    const title = document.createElement("div");
    title.className = "array-title";
    title.textContent = names.join(", ");
    if (meta) {
        const span = document.createElement("span");
        span.className = "meta";
        span.textContent = meta;
        title.append(span);
    }
    const scroll = document.createElement("div");
    scroll.className = "array-scroll";
    scroll.append(drawing);
    div.append(title, scroll);
    return div;
}
