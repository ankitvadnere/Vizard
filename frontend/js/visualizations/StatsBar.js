// Running operation counts for the current step. A counter that this step increased is highlighted,
// so you can see exactly which line made a comparison, a swap or a call.

const COUNTERS = [
    ["comparisons", "Comparisons", "Conditions that compared array elements"],
    ["swaps", "Swaps", "Two array cells exchanged values"],
    ["arrayReads", "Array reads", "Array elements read"],
    ["arrayWrites", "Array writes", "Array elements assigned"],
    ["loopIterations", "Loop iterations", "Loop iterations started"],
    ["methodCalls", "Method calls", "Calls to your methods (main not counted)"],
    ["maxDepth", "Max depth", "Deepest call stack so far (1 = only main)"],
];

export class StatsBar {
    constructor(container) {
        this.container = container;
        this.cells = new Map();
        for (const [key, label, help] of COUNTERS) {
            const cell = document.createElement("div");
            cell.className = "stat";
            cell.title = help;
            const value = document.createElement("span");
            value.className = "stat-value";
            const name = document.createElement("span");
            name.className = "stat-label";
            name.textContent = label;
            cell.append(value, name);
            container.append(cell);
            this.cells.set(key, { cell, value });
        }
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
