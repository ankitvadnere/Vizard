// Running operation counts for the current step. Only the counters this program uses are shown
// (decided from the trace's last step). A counter that this step increased is highlighted, so you
// can see exactly which line made a comparison, a push or a call.

const COUNTERS = [
    ["comparisons", "Comparisons", "Conditions that compared array elements or node values"],
    ["swaps", "Swaps", "Two array cells exchanged values"],
    ["arrayReads", "Array reads", "Array elements read"],
    ["arrayWrites", "Array writes", "Array elements assigned"],
    ["loopIterations", "Loop iterations", "Loop iterations started"],
    ["methodCalls", "Method calls", "Calls to your methods (main not counted)"],
    ["maxDepth", "Max depth", "Deepest call stack so far (1 = only main)"],
    ["pushes", "Pushes", "Elements pushed onto a stack"],
    ["pops", "Pops", "Elements popped from a stack"],
    ["enqueues", "Enqueues", "Elements added to a queue or priority queue"],
    ["dequeues", "Dequeues", "Elements taken from the front of a queue or priority queue"],
    ["inserts", "Inserts", "add, put and set on lists, maps and sets"],
    ["removes", "Removes", "Removals from lists, maps and sets"],
    ["lookups", "Lookups", "get, contains, containsKey and peek"],
];
const MIN_SHOWN = ["comparisons", "swaps", "loopIterations"];

export class StatsBar {
    constructor(container) {
        this.container = container;
        this.cells = new Map();
    }

    /** Shows the counters this run uses: those not zero at the end (and a few basics if all are). */
    configure(finalStats) {
        this.container.replaceChildren();
        this.cells.clear();
        const used = COUNTERS.filter(([key]) => {
            const value = finalStats?.[key] ?? 0;
            return key === "maxDepth" ? value > 1 : value > 0;
        });
        const chosen = used.length ? used : COUNTERS.filter(([key]) => MIN_SHOWN.includes(key));
        for (const [key, label, help] of chosen) {
            const cell = document.createElement("div");
            cell.className = "stat";
            cell.title = help;
            const value = document.createElement("span");
            value.className = "stat-value";
            const name = document.createElement("span");
            name.className = "stat-label";
            name.textContent = label;
            cell.append(value, name);
            this.container.append(cell);
            this.cells.set(key, { cell, value });
        }
        this.container.style.setProperty("--stat-count", String(chosen.length));
    }

    hide() {
        this.container.hidden = true;
    }

    /** @param previous stats of the step before (null at the first step) */
    render(stats, previous) {
        if (!stats) {
            this.hide();
            return;
        }
        this.container.hidden = false;
        for (const [key, { cell, value }] of this.cells) {
            value.textContent = String(stats[key] ?? 0);
            const bumped = previous && (stats[key] ?? 0) > (previous[key] ?? 0);
            cell.classList.toggle("bumped", Boolean(bumped));
        }
    }
}
