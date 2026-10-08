// Composes the Visualization pane: insight strip, arrays, call stack and legend.

import { ArrayVisualizer } from "./ArrayVisualizer.js";
import { InsightStrip } from "./InsightStrip.js";
import { CallStackVisualizer } from "./CallStackVisualizer.js";
import { StatsBar } from "./StatsBar.js";

export class VisualizationPanel {
    constructor() {
        this.label = document.getElementById("viz-label");
        this.arraysEl = document.getElementById("arrays");
        this.legend = document.getElementById("viz-legend");
        this.insights = new InsightStrip(document.getElementById("insights"));
        this.arrays = new ArrayVisualizer(this.arraysEl);
        this.callStack = new CallStackVisualizer(document.getElementById("call-stack"));
        this.stats = new StatsBar(document.getElementById("stats"));
    }

    showEmpty(message = "Press Step through to see arrays, conditions and the call stack change line by line.") {
        this.label.textContent = "";
        this.insights.clear();
        this.arraysEl.replaceChildren(note("viz-empty", message));
        this.callStack.showEmpty();
        this.stats.hide();
        this.legend.hidden = true;
    }

    /**
     * @param animate  true when arriving from the previous step, so changes can be animated
     * @param previous the step that executed just before (for highlighting counters it increased)
     */
    render(step, { animate = false, previous = null } = {}) {
        this.label.textContent = `line ${step.line}`;
        this.insights.render(step);
        const count = this.arrays.render(step, { animate });
        if (count === 0) {
            this.arraysEl.append(note("viz-note", "No arrays in this method."));
        }
        this.legend.hidden = count === 0;
        this.callStack.render(step);
        this.stats.render(step.stats, previous?.stats ?? null);
    }
}

function note(className, text) {
    const p = document.createElement("p");
    p.className = className;
    p.textContent = text;
    return p;
}
