// The growth check: the program's operation counts at growing input sizes, on log-log axes, with the
// best-fitting curve drawn through them. On log-log axes nᵏ is a straight line of slope k, so n, n² and
// n³ are lines of different steepness, and 2ⁿ or n! bend upwards.

import { el, svgRoot, text } from "./svg.js";

const W = 520;
const H = 250;
const M = { left: 58, right: 16, top: 14, bottom: 38 };

/** ln f(n), matching the backend's GrowthFitter. */
const LN_F = {
    "1": () => 0,
    "log n": (n) => Math.log(Math.log2(n)),
    n: (n) => Math.log(n),
    "n log n": (n) => Math.log(n) + Math.log(Math.log2(n)),
    "n²": (n) => 2 * Math.log(n),
    "n³": (n) => 3 * Math.log(n),
    "n!": (n) => lnFact(n),
    "n · n!": (n) => Math.log(n) + lnFact(n),
};

function lnFact(n) {
    let s = 0;
    for (let i = 2; i <= n; i++) s += Math.log(i);
    return s;
}

const lnF = (fit, n) => (fit.base ? n * Math.log(fit.base) : LN_F[fit.curve](n));
const isExponential = (fit) => fit.base != null || fit.curve.endsWith("n!");
const growthWords = (fit) => (fit.base ? `adding 1 to n multiplies the work by about ${fit.base.toFixed(2)}`
    : WHAT_DOUBLING_DOES[fit.curve]);

const WHAT_DOUBLING_DOES = {
    "1": "doubling n leaves the work about the same",
    "log n": "doubling n adds about the same amount of work each time",
    n: "doubling n doubles the work",
    "n log n": "doubling n a little more than doubles the work",
    "n²": "doubling n multiplies the work by about 4",
    "n³": "doubling n multiplies the work by about 8",
    "n!": "adding 1 to n multiplies the work by about n",
    "n · n!": "adding 1 to n multiplies the work by a little more than n",
};

/** The whole growth section: verdict, chart, table. `expected` = ["O(n²)", ...] from recognition/estimates. */
export function growthResult(response, expected) {
    const box = document.createElement("div");
    box.className = "growth-result";
    if (!response.points?.length || !response.fits?.length) {
        box.append(note("growth-note", response.reason ?? response.execution?.message ?? "No measurement."));
        if (response.points?.length) box.append(table(response.points));
        return box;
    }
    const best = response.fits[0];
    const second = response.fits[1];
    const verdict = document.createElement("p");
    verdict.className = "growth-verdict";
    const strong = document.createElement("strong");
    strong.textContent = best.curve;
    verdict.append("Closest curve: ", strong, ` (operations ≈ c · ${best.curve}). In these runs, ${growthWords(best)}.`);
    box.append(verdict);

    const match = expected.find((e) => normal(e) === normal(best.curve));
    const expMatch = best.base ? expected.find((e) => /\dⁿ|φⁿ/.test(e)) : null;
    if (expected.length) {
        box.append(note("growth-note", match
            ? `This agrees with ${match} found from the code.`
            : expMatch ? `Exponential, as ${expMatch} from the code says. ${expMatch} is an upper bound; these runs grew `
            + `like ${best.curve}${Math.abs(best.base - 1.618) < 0.06 ? " (φⁿ, the golden ratio: the growth of Fibonacci numbers themselves)" : ""}.`
            : `From the code: ${expected.join(", ")}. A measurement can differ: random input is not always the worst `
            + "case, small sizes carry constant overhead, and work inside library methods (Arrays.sort, HashMap) is "
            + "not counted."));
    }
    box.append(chart(response.points, best, second));
    const details = [`Measured: ${response.measured}, at ${response.points.length} sizes.`];
    if (response.slope != null && !isExponential(best)) {
        details.push(`Slope of the log-log line over the larger sizes: ${response.slope.toFixed(2)} (nᵏ has slope k).`);
    }
    if (response.stopped) details.push(`Stopped: ${response.stopped}.`);
    box.append(note("growth-note", details.join(" ")));
    const more = document.createElement("details");
    more.className = "algo-why";
    const summary = document.createElement("summary");
    summary.textContent = "Measurements and how close each curve fits";
    more.append(summary, table(response.points), fitList(response.fits));
    box.append(more);
    return box;
}

function normal(s) {
    return s.replace(/^O\(|\)$/g, "").replace(/\s+/g, "");
}

function chart(points, best, second) {
    const ns = points.map((p) => p.n);
    const ops = points.map((p) => p.operations);
    // Exponential growth is a straight line with n on a plain scale; polynomials with n on a log scale.
    const logX = !isExponential(best);
    const sx = (n) => (logX ? Math.log(n) : n);
    const x0 = sx(Math.min(...ns));
    const x1 = sx(Math.max(...ns));
    const curveAt = (fit, n) => Math.exp(fit.logScale + lnF(fit, n));
    const samples = (fit) => {
        const out = [];
        for (let i = 0; i <= 64; i++) {
            const t = x0 + ((x1 - x0) * i) / 64;
            const n = logX ? Math.exp(t) : t;
            out.push([n, curveAt(fit, n)]);
        }
        return out;
    };
    const bestLine = samples(best);
    const y0 = Math.log(Math.max(1, Math.min(...ops) / 3));
    const y1 = Math.log(Math.max(...ops) * 3);
    const px = (n) => M.left + ((sx(n) - x0) / (x1 - x0 || 1)) * (W - M.left - M.right);
    const py = (v) => H - M.bottom - ((Math.log(Math.max(v, 1e-9)) - y0) / (y1 - y0 || 1)) * (H - M.top - M.bottom);

    const svg = svgRoot(W, H, `Operations against n on log-log axes; closest curve ${best.curve}`);
    svg.classList.add("growth-svg");
    svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
    svg.removeAttribute("width");
    svg.removeAttribute("height");

    // grid: powers of ten on y, the measured sizes on x
    for (let e = Math.ceil(y0 / Math.LN10); e <= Math.floor(y1 / Math.LN10); e++) {
        const y = py(10 ** e);
        svg.append(el("line", { class: "growth-grid", x1: M.left, x2: W - M.right, y1: y, y2: y }));
        svg.append(text("growth-tick", M.left - 6, y, e < 4 ? String(10 ** e) : `10${sup(e)}`, "end"));
    }
    const shown = ns.length > 8 ? ns.filter((_, i) => i % 2 === 0 || i === ns.length - 1) : ns;
    for (const n of shown) {
        svg.append(el("line", { class: "growth-grid", x1: px(n), x2: px(n), y1: M.top, y2: H - M.bottom }));
        svg.append(text("growth-tick", px(n), H - M.bottom + 14, String(n), "middle"));
    }
    svg.append(text("growth-axis", (M.left + W - M.right) / 2, H - 6, logX ? "n (input size, log scale)" : "n (input size)", "middle"));
    const yl = text("growth-axis", 12, (M.top + H - M.bottom) / 2, "operations (log scale)", "middle");
    yl.setAttribute("transform", `rotate(-90 12 ${(M.top + H - M.bottom) / 2})`);
    svg.append(yl);

    // only the part of a curve inside the plot
    const path = (pts) => pts.filter(([, v]) => Math.log(v) >= y0 && Math.log(v) <= y1)
        .map(([n, v], i) => `${i ? "L" : "M"}${px(n).toFixed(1)} ${py(v).toFixed(1)}`).join(" ");
    if (second) svg.append(el("path", { class: "growth-second", d: path(samples(second)) }));
    svg.append(el("path", { class: "growth-fit", d: path(bestLine) }));
    for (const p of points) {
        const g = el("g", { class: "growth-point", tabindex: 0 });
        g.append(el("circle", { cx: px(p.n), cy: py(p.operations), r: 9, class: "hit" }));
        g.append(el("circle", { cx: px(p.n), cy: py(p.operations), r: 4.5, class: "dot" }));
        g.append(el("title", {}, `n = ${p.n}: ${p.operations.toLocaleString()} operations (${p.ms} ms)`));
        svg.append(g);
    }

    const wrap = document.createElement("figure");
    wrap.className = "growth-figure";
    wrap.append(svg);
    const legend = document.createElement("figcaption");
    legend.className = "legend graph-legend";
    const item = (cls, label) => {
        const span = document.createElement("span");
        const sw = document.createElement("i");
        sw.className = `swatch ${cls}`;
        span.append(sw, label);
        return span;
    };
    legend.append(item("growth-swatch-dot", "measured"), item("growth-swatch-fit", `c · ${best.curve} (closest)`));
    if (second) legend.append(item("growth-swatch-second", `c · ${second.curve} (next closest)`));
    wrap.append(legend);
    return wrap;
}

function table(points) {
    const t = document.createElement("table");
    t.className = "cost-table";
    const head = t.createTHead().insertRow();
    for (const h of ["n", "Operations", "Ratio to previous", "Time"]) {
        const th = document.createElement("th");
        th.textContent = h;
        head.append(th);
    }
    const body = t.createTBody();
    points.forEach((p, i) => {
        const r = body.insertRow();
        r.insertCell().textContent = String(p.n);
        r.insertCell().textContent = p.operations.toLocaleString();
        r.insertCell().textContent = i === 0 || !points[i - 1].operations ? "" : `× ${(p.operations / points[i - 1].operations).toFixed(2)}`;
        r.insertCell().textContent = `${p.ms} ms`;
    });
    return t;
}

function fitList(fits) {
    const p = document.createElement("p");
    p.className = "growth-note";
    p.textContent = "Fit error (0 = perfect, on the log scale): "
        + fits.map((f) => `${f.curve} ${f.error.toFixed(2)}`).join(" · ");
    return p;
}

function note(cls, content) {
    const p = document.createElement("p");
    p.className = cls;
    p.textContent = content;
    return p;
}

function sup(e) {
    return String(e).split("").map((c) => "⁰¹²³⁴⁵⁶⁷⁸⁹"[Number(c)]).join("");
}
