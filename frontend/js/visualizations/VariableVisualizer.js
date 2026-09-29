// Lists the variables of the current method (and static fields), marking what just changed.

import { formatValue, methodLabel } from "../values.js";

export class VariableVisualizer {
    constructor(container, frameLabel) {
        this.container = container;
        this.frameLabel = frameLabel;
    }

    showEmpty(message = "Press Step through to watch variables change line by line.") {
        this.frameLabel.textContent = "";
        this.container.innerHTML = "";
        const p = document.createElement("p");
        p.className = "empty-state";
        p.textContent = message;
        this.container.append(p);
    }

    /** @param previous the step that ran just before, for change highlighting (may be null) */
    render(step, previous) {
        const frame = step.stack[0];
        this.frameLabel.textContent = `in ${methodLabel(frame)}`;
        this.container.replaceChildren();

        // Compare with the previous step only if it was in the same method call.
        const sameCall = previous && previous.depth === step.depth
            && previous.stack[0].methodName === frame.methodName;
        const before = sameCall ? valuesByName(previous.stack[0].variables, previous.heap) : null;

        const locals = frame.variables.filter((v) => v.name !== "args" || v.value.kind !== "ref"
            || (step.heap[v.value.ref]?.length ?? 0) > 0); // hide the empty String[] args
        if (locals.length === 0 && step.statics.length === 0) {
            this.#note("No variables yet.");
            return;
        }
        for (const v of locals) {
            this.container.append(this.#row(v, step.heap, before));
        }

        if (step.statics.length > 0) {
            const beforeStatics = previous ? valuesByName(previous.statics, previous.heap) : null;
            const heading = document.createElement("div");
            heading.className = "var-group";
            heading.textContent = "Static fields";
            this.container.append(heading);
            for (const v of step.statics) {
                this.container.append(this.#row(v, step.heap, beforeStatics));
            }
        }
    }

    #row(variable, heap, before) {
        const text = formatValue(variable.value, heap);
        const row = document.createElement("div");
        row.className = "var-row";
        if (before) {
            if (!before.has(variable.name)) row.dataset.change = "new";
            else if (before.get(variable.name) !== text) row.dataset.change = "changed";
        }

        const name = document.createElement("span");
        name.className = "var-name";
        name.textContent = variable.name;
        const type = document.createElement("span");
        type.className = "var-type";
        type.textContent = variable.type;
        name.append(type);

        const value = document.createElement("span");
        value.className = `var-value var-kind-${variable.value.kind}`;
        value.textContent = text;

        row.append(name, value);
        return row;
    }

    #note(message) {
        const p = document.createElement("p");
        p.className = "empty-state";
        p.textContent = message;
        this.container.append(p);
    }
}

function valuesByName(variables, heap) {
    return new Map(variables.map((v) => [v.name, formatValue(v.value, heap)]));
}
