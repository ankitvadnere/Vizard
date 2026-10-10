// Composes the Visualization pane: insight strip, arrays, data structures, call stack and legend.

import { ArrayVisualizer } from "./ArrayVisualizer.js";
import { InsightStrip } from "./InsightStrip.js";
import { CallStackVisualizer } from "./CallStackVisualizer.js";
import { StatsBar } from "./StatsBar.js";
import { StructureVisualizer } from "./StructureVisualizer.js";

export class VisualizationPanel {
    constructor() {
        this.label = document.getElementById("viz-label");
        this.arraysEl = document.getElementById("arrays");
        this.legend = document.getElementById("viz-legend");
        this.insights = new InsightStrip(document.getElementById("insights"));
        this.arrays = new ArrayVisualizer(this.arraysEl);
        this.structuresEl = document.getElementById("structures");
        this.structures = new StructureVisualizer(this.structuresEl);
        this.callStack = new CallStackVisualizer(document.getElementById("call-stack"));
        this.stats = new StatsBar(document.getElementById("stats"));
    }

    showEmpty(message = "Press Step through to see arrays, data structures, conditions and the call stack change line by line.") {
        this.label.textContent = "";
        this.insights.clear();
        this.arraysEl.replaceChildren(note("viz-empty", message));
        this.structuresEl.replaceChildren();
        this.callStack.showEmpty();
        this.stats.hide();
        this.legend.hidden = true;
    }

    /**
     * @param animate  true when arriving from the previous step, so changes can be animated
     * @param previous the step that executed just before (for highlighting counters it increased)
     */
    /** Called once per trace with its last step, so the stats bar shows only the counters this run uses. */
    load(lastStep) {
        this.stats.configure(lastStep?.stats ?? null);
    }

    render(step, { animate = false, previous = null } = {}) {
        this.label.textContent = `line ${step.line}`;
        this.insights.render(step);
        const arrays = this.arrays.render(step, { animate });
        const structures = this.structures.render(step, previous);
        if (arrays + structures === 0) {
            this.arraysEl.append(note("viz-note", "No arrays or data structures in this method."));
        }
        this.legend.hidden = arrays === 0;
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
