// The Complexity tab: algorithms Vizard recognised, their textbook complexity, why they were
// recognised, and the worst-case operation count for this input next to this run's live count.

import { growthResult } from "./GrowthChart.js";

const METRIC_UNITS = {
    comparisons: "comparisons", loopIterations: "loop iterations", edgesExamined: "checks", nodesExplored: "nodes", nodesGenerated: "nodes",
};

export class ComplexityPanel {
    /** @param onMeasure () => Promise<GrowthResponse>: runs the growth check for the traced program */
    constructor(container, dot, onMeasure) {
        this.container = container;
        this.dot = dot;
        this.onMeasure = onMeasure;
        this.analysis = null;
        this.meters = [];
    }

    showEmpty(message = "Press Step through to analyse the program.") {
        this.analysis = null;
        this.meters = [];
        this.dot.hidden = true;
        this.container.replaceChildren(paragraph("complexity-empty", message));
    }

    /** Builds the cards once per trace; render(step) then only updates the live counts. */
    load(analysis, truncated) {
        this.analysis = analysis;
        this.meters = [];
        this.container.replaceChildren();
        const algorithms = analysis?.algorithms ?? [];
        const structures = analysis?.structures ?? [];
        const estimates = analysis?.estimates ?? [];
        this.dot.hidden = algorithms.length === 0 && structures.length === 0 && estimates.length === 0;

        if (algorithms.length === 0 && estimates.length === 0) {
            this.container.append(
                paragraph("complexity-empty", "No known algorithm recognised, and no loops or recursion to estimate."),
                paragraph("complexity-footnote",
                    "Vizard names the complexity of algorithms it recognises from the structure of the code, and "
                    + "estimates other methods from their loops, calls and recursion. The operation counts are "
                    + "still exact for this run."));
        }
        for (const algorithm of algorithms) {
            this.container.append(this.#card(algorithm, analysis.input, truncated));
        }
        if (estimates.length > 0) {
            this.container.append(estimateSection(estimates, algorithms.length > 0));
        }
        if (structures.length > 0) {
            this.container.append(structureSection(structures));
        }
        const expected = [...algorithms.map((a) => a.complexity.worst), ...estimates.map((e) => e.time).filter(Boolean)];
        if (analysis) this.container.append(this.#growthSection(expected));
        if (algorithms.length > 0) {
            this.container.append(paragraph("complexity-footnote",
                "Complexities come from a catalogue of known algorithms, matched by code structure and adjusted "
                + "for details Vizard can see (such as an early-exit flag). n is the length of the largest array "
                + "the program used, or the number of nodes in its tree or list; V and E are the vertices and edges "
                + "of its graph."));
        }
    }

    /** "Measure growth": runs the program at bigger and bigger inputs (on request: it takes a few seconds). */
    #growthSection(expected) {
        const section = document.createElement("section");
        section.className = "growth";
        const h = document.createElement("h3");
        h.textContent = "Growth check";
        section.append(h, paragraph("complexity-footnote",
            "Runs the program again with bigger and bigger inputs made up by Vizard (the array, n or strings written "
            + "in your code), counting loop iterations and calls of your methods, and finds the curve that fits."));
        const button = document.createElement("button");
        button.type = "button";
        button.className = "button secondary growth-button";
        button.textContent = "Measure growth";
        const result = document.createElement("div");
        button.addEventListener("click", async () => {
            if (!this.onMeasure) return;
            button.disabled = true;
            button.textContent = "Measuring… (runs the program up to a few dozen times)";
            section.setAttribute("aria-busy", "true");
            result.replaceChildren();
            try {
                const response = await this.onMeasure();
                if (!section.isConnected) return;
                result.append(growthResult(response, expected));
            } finally {
                button.disabled = false;
                button.textContent = "Measure again";
                section.removeAttribute("aria-busy");
            }
        });
        section.append(button, result);
        return section;
    }

    render(step) {
        for (const meter of this.meters) meter(step.stats);
    }

    #card(algorithm, input, truncated) {
        const card = document.createElement("article");
        card.className = "algo-card";

        const head = document.createElement("header");
        const title = document.createElement("h3");
        title.textContent = algorithm.name;
        const where = document.createElement("span");
        where.className = "algo-where";
        where.textContent = `${algorithm.method}(), line ${algorithm.line}`;
        const tag = document.createElement("span");
        tag.className = "algo-tag";
        tag.textContent = algorithm.category;
        head.append(title, where, tag);
        card.append(head);

        const c = algorithm.complexity;
        const table = document.createElement("dl");
        table.className = "big-o";
        for (const [label, value] of [["Best", c.best], ["Average", c.average], ["Worst", c.worst], ["Space", c.space]]) {
            const cell = document.createElement("div");
            const dt = document.createElement("dt");
            dt.textContent = label;
            const dd = document.createElement("dd");
            dd.textContent = value;
            cell.append(dt, dd);
            table.append(cell);
        }
        if (c.stable !== undefined && c.stable !== null) {
            const cell = document.createElement("div");
            const dt = document.createElement("dt");
            dt.textContent = "Stable";
            const dd = document.createElement("dd");
            dd.textContent = c.stable ? "Yes" : "No";
            cell.append(dt, dd);
            table.append(cell);
        }
        card.append(table);
        if (c.note) card.append(paragraph("algo-note", c.note));

        if (algorithm.bound && algorithm.bound.value != null && (input || algorithm.bound.size || graphSize(algorithm))) {
            card.append(this.#meter(algorithm.bound, input, truncated, graphSize(algorithm)));
        }
        if (algorithm.measures?.length) {
            card.append(measureList(algorithm.measures));
        }

        const why = document.createElement("details");
        why.className = "algo-why";
        const summary = document.createElement("summary");
        summary.textContent = "Why Vizard recognised it";
        const list = document.createElement("ul");
        for (const reason of algorithm.evidence) {
            const li = document.createElement("li");
            li.textContent = reason;
            list.append(li);
        }
        why.append(summary, list);
        card.append(why);
        return card;
    }

    /** "n = 4 (arr): worst case n(n−1)/2 = 6 comparisons" + a live bar for this run. */
    #meter(bound, input, truncated, graph) {
        const unit = METRIC_UNITS[bound.metric] ?? bound.metric;
        const box = document.createElement("div");
        box.className = "bound";

        const theory = document.createElement("p");
        theory.className = "bound-theory";
        const size = bound.size ?? (bound.metric === "edgesExamined" && graph ? graph : `n = ${input.n} (${input.name})`);
        theory.append(`For ${size}: ${bound.description}, `);
        const formula = document.createElement("code");
        formula.textContent = `${bound.formula} = ${bound.value}`;
        const scope = bound.metric === "nodesExplored" || bound.metric === "nodesGenerated" ? ""
            : bound.scope === "per search" ? " per search" : bound.scope === "per traversal" ? " per traversal" : "";
        theory.append(formula, ` ${unit}${scope}.`);

        const row = document.createElement("div");
        row.className = "bound-row";
        const track = document.createElement("div");
        track.className = "bound-track";
        const fill = document.createElement("div");
        fill.className = "bound-fill";
        track.append(fill);
        const label = document.createElement("span");
        label.className = "bound-label";
        row.append(track, label);
        box.append(theory, row);

        this.meters.push((stats) => {
            const actual = stats?.[bound.metric] ?? 0;
            const share = bound.value > 0 ? Math.min(1, actual / bound.value) : 0;
            fill.style.width = `${share * 100}%`;
            fill.classList.toggle("over", actual > bound.value);
            label.textContent = `This run so far: ${actual}${truncated ? " (recorded steps only)" : ""}`;
        });
        return box;
    }
}

/** Static estimates for the methods that are not recognised algorithms. */
function estimateSection(estimates, afterAlgorithms) {
    const section = document.createElement("section");
    section.className = "estimates";
    const h = document.createElement("h3");
    h.textContent = afterAlgorithms ? "Other methods (estimated)" : "Estimated complexity";
    section.append(h);
    section.append(paragraph("complexity-footnote",
        "Not a recognised algorithm, so Vizard reads the code instead: how many times each loop can run, how loops "
        + "nest, what each call costs, and the recurrence of a recursive method. This is an upper bound for the worst "
        + "case: an early exit or the data itself can make a run cheaper."));
    for (const e of estimates) {
        const card = document.createElement("article");
        card.className = "algo-card estimate-card";
        const head = document.createElement("header");
        const title = document.createElement("h3");
        title.textContent = `${e.method}()`;
        const where = document.createElement("span");
        where.className = "algo-where";
        where.textContent = `line ${e.line}`;
        const tag = document.createElement("span");
        tag.className = "algo-tag estimate-tag";
        tag.textContent = "Estimate";
        head.append(title, where, tag);
        card.append(head);

        if (e.time) {
            const table = document.createElement("dl");
            table.className = "big-o";
            const cells = [["Time (worst case)", e.time]];
            if (e.space) cells.push(["Extra space", e.space]);
            for (const [label, value] of cells) {
                const cell = document.createElement("div");
                const dt = document.createElement("dt");
                dt.textContent = label;
                const dd = document.createElement("dd");
                dd.textContent = value;
                cell.append(dt, dd);
                table.append(cell);
            }
            card.append(table);
        } else {
            card.append(paragraph("algo-note", "Vizard could not form an estimate for this method."));
        }
        if (e.recurrence) {
            const rec = document.createElement("p");
            rec.className = "recurrence";
            const code = document.createElement("code");
            code.textContent = e.recurrence;
            rec.append(code);
            if (e.rule) rec.append(` — ${e.rule}`);
            card.append(rec);
        }
        if (e.legend?.length) card.append(paragraph("estimate-legend", `where ${e.legend.join("; ")}`));

        const why = document.createElement("details");
        why.className = "algo-why";
        why.open = !afterAlgorithms && estimates.length <= 2;
        const summary = document.createElement("summary");
        summary.textContent = "How it was estimated";
        const list = document.createElement("ul");
        list.className = "estimate-steps";
        for (const step of e.steps) {
            const li = document.createElement("li");
            const depth = step.length - step.trimStart().length;
            li.style.paddingLeft = `${depth * 0.6}em`;
            li.textContent = step.trim();
            list.append(li);
        }
        why.append(summary, list);
        card.append(why);
        section.append(card);
    }
    return section;
}

/** "V = 6, E = 7" from a graph algorithm's measures, or null. */
function graphSize(algorithm) {
    const v = algorithm.measures?.find((m) => m.label.startsWith("Vertices"));
    const e = algorithm.measures?.find((m) => m.label.startsWith("Edges"));
    return v && e ? `V = ${v.value}, E = ${e.value}` : null;
}

/** "Nodes n 7 · Height h 2 · ..." measured from this run. */
function measureList(measures) {
    const dl = document.createElement("dl");
    dl.className = "measures";
    for (const m of measures) {
        const cell = document.createElement("div");
        const dt = document.createElement("dt");
        dt.textContent = m.label;
        const dd = document.createElement("dd");
        dd.textContent = m.value;
        cell.append(dt, dd);
        dl.append(cell);
    }
    const box = document.createElement("div");
    box.className = "measure-box";
    box.append(paragraph("measure-title", "Measured in this run"), dl);
    return box;
}

const ROLE_WORDS = {
    stack: "a stack", queue: "a queue", deque: "a deque", list: "a list",
    "priority-queue": "a priority queue", map: "a map", set: "a set",
};

/** Each collection the run used, the calls it made on it, and what one call costs. */
function structureSection(structures) {
    const section = document.createElement("section");
    section.className = "structures-used";
    const h = document.createElement("h3");
    h.textContent = "Data structures used";
    section.append(h);
    for (const s of structures) {
        const card = document.createElement("article");
        card.className = "algo-card";
        const head = document.createElement("header");
        const title = document.createElement("h4");
        title.textContent = s.variable ?? s.type;
        const where = document.createElement("span");
        where.className = "algo-where";
        where.textContent = `${s.type}, used as ${ROLE_WORDS[s.role] ?? s.role}`;
        head.append(title, where);
        card.append(head);

        const table = document.createElement("table");
        table.className = "cost-table";
        const headRow = table.createTHead().insertRow();
        for (const label of ["Operation", "Calls in this run", "Cost of one call"]) {
            const th = document.createElement("th");
            th.textContent = label;
            headRow.append(th);
        }
        const body = table.createTBody();
        for (const op of s.operations) {
            const row = body.insertRow();
            row.insertCell().textContent = op.method;
            row.insertCell().textContent = String(op.count);
            row.insertCell().textContent = op.cost;
        }
        card.append(table);
        if (s.note) card.append(paragraph("algo-note", s.note));
        section.append(card);
    }
    return section;
}

function paragraph(className, text) {
    const p = document.createElement("p");
    p.className = className;
    p.textContent = text;
    return p;
}
