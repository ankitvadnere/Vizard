// The Complexity tab: algorithms Vizard recognised, their textbook complexity, why they were
// recognised, and the worst-case operation count for this input next to this run's live count.

const METRIC_UNITS = { comparisons: "comparisons", loopIterations: "loop iterations" };

export class ComplexityPanel {
    constructor(container, dot) {
        this.container = container;
        this.dot = dot;
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
        this.dot.hidden = algorithms.length === 0;

        if (algorithms.length === 0) {
            this.container.append(
                paragraph("complexity-empty",
                    "No known algorithm recognised in this program."),
                paragraph("complexity-footnote",
                    "Vizard reports complexity only for algorithms it can identify from the structure of the code "
                    + "(bubble, selection, insertion, merge and quick sort; linear and binary search). It does not "
                    + "guess the complexity of arbitrary code. The operation counts below the visualization are "
                    + "still exact for this run."));
            return;
        }
        for (const algorithm of algorithms) {
            this.container.append(this.#card(algorithm, analysis.input, truncated));
        }
        this.container.append(paragraph("complexity-footnote",
            "Complexities come from a catalogue of known algorithms, matched by code structure and adjusted "
            + "for details Vizard can see (such as an early-exit flag). n is the length of the largest array "
            + "the program used."));
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

        if (algorithm.bound && input) {
            card.append(this.#meter(algorithm.bound, input, truncated));
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
    #meter(bound, input, truncated) {
        const unit = METRIC_UNITS[bound.metric] ?? bound.metric;
        const box = document.createElement("div");
        box.className = "bound";

        const theory = document.createElement("p");
        theory.className = "bound-theory";
        theory.append(`For n = ${input.n} (${input.name}): ${bound.description}, `);
        const formula = document.createElement("code");
        formula.textContent = `${bound.formula} = ${bound.value}`;
        theory.append(formula, ` ${unit}${bound.scope === "per search" ? " per search" : ""}.`);

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

function paragraph(className, text) {
    const p = document.createElement("p");
    p.className = className;
    p.textContent = text;
    return p;
}
